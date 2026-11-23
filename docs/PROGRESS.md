# Build progress

Agents update this file at the end of every task and every phase. Keep it short and factual.

## Status

| Phase | Name | Status | Branch | Closed on |
| --- | --- | --- | --- | --- |
| 00 | Foundation | done | phase/00-foundation | 2026-11-22 |
| 01 | Catalog and pricing | done | phase/01-catalog-and-pricing | 2026-11-22 |
| 02 | Inventory and procurement | in progress | phase/02-inventory-and-procurement | |
| 03 | Sales, payments, sync and printing | not started | | |
| 04 | Finance and reporting | not started | | |
| 05 | Multi-business onboarding | not started | | |
| 06 | Scale-out to microservices | not started | | |

Status values: `not started` · `in progress` · `blocked` · `done`.

## Current phase

- Phase: 02 — Inventory, procurement and parties (started 2026-11-23, branch `phase/02-inventory-and-procurement`)
- Current task: T02-06 Inventory listeners for purchasing
- Next step (exact, so a new session can resume): in `modules/inventory`, add `procurement::api` to the allowed dependencies and a `GoodsReceived` listener (inbox-guarded) that posts RECEIPT movements per line (cost = line total, batches from batch code/expiry, returns leave at current cost) and publishes `StockReceived`, with `/run-phase 02`. Task order: T02-05, then T02-08 (procurement) before T02-06 (its GoodsReceived listener needs procurement's events), then T02-07 (the reorder SPI is procurement's), then T02-09.

## Task checklist (current phase)

Phase 02:
- [x] T02-01 Contracts (`party.yaml`, `inventory.yaml`, `procurement.yaml`, events in AsyncAPI) — additions beyond the endpoint lists: `POST /goods-receipts/{id}/cancel` for drafts; stock events carry one source with its lines (`StockReceived` also for openings and transfers, with cost `corrections`); `StockIssued` is defined with its sales listener in phase 03
- [x] T02-02 Party module — address and credit terms are columns of `party`; phones E.164 read in LK; one create endpoint (`party:create-customer`) with `party:write` checked for suppliers and `force`; events without contact details; trigram name search (ADR-022)
- [x] T02-03 Inventory replicas — `stock_item` (units, states, tracking), `stock_product` (for valuation by product and category), `location_ref`, `transformation_def_ref` (one row per published version); version-guarded upserts and tombstones as in pricing
- [x] T02-04 Inventory ledger core — `stock_movement` partitioned by month (2026–2028 + default, RLS on every partition, INSERT/SELECT only); balances keep `stock_value = Σ cost_amount`; negative stock is trued up on the next receipt with a CORRECTION movement under both policies; FIFO layers keep their value; FEFO spreads issues of batch-tracked variants; the low flag lives on `reorder_policy`; 50-thread concurrency IT
- [x] T02-05 Transformations, adjustments, transfers, opening balances, counts — transformations lock inputs and outputs first, issue inputs at current cost and share it by costSharePct (largest remainder); count variance = counted − system at the line's snapshot (movements since are in both); openings are once per variant/location/state, checked under the balance lock; transfers mirror the outgoing batches and costs
- [ ] T02-06 Inventory listeners for purchasing
- [ ] T02-07 Valuation, reorder list, rebuild
- [x] T02-08 Procurement module — done before T02-06 because inventory listens to `GoodsReceived`; returns are receipts with negative lines naming the delivery they correct, at most what arrived less earlier returns (checked again under lock when posting); over-receipt allowed; numbers taken at creation; invoice due date from the supplier's payment terms; the reorder SPI default is a `@Fallback` bean (ADR-023)
- [ ] T02-09 Flow test with the pilot shop (`ReceiveAndTransformFlowIT`)
- [ ] Definition of Done
  - [ ] Every PTY/INV/PRO rule has a named test; flow IT passes with exact numbers
  - [ ] Ledger tables have no UPDATE/DELETE grants (RLS coverage test extended to check grants)
  - [ ] Concurrency IT and rebuild-equality IT pass
  - [ ] PIT ≥ 70% on `party.domain`, `inventory.domain`, `procurement.domain`
  - [ ] Contracts, READMEs, `.http` files updated; PROGRESS summary written

## Open questions

_One line each: date, question, assumption taken, ADR link._

- 2026-11-21 — Settings sanity limits (returnWindowDays ≤ 365, offlineMaxHours ≤ 720, periodLockDays ≤ 31) and numbering prefixes (PO-, GRN-, CN-) are assumptions in the schemas; confirm with the client.
- 2026-11-21 — Default role permissions follow 08-security.md §3 literally, so only OWNER has `tenant:read`/`tenant:*`; MANAGER etc. may need `tenant:read` for settings — assumption: least privilege, tenants extend roles via the iam API (ADR-014).
- 2026-11-22 — Users without e-mail get their first password from an administrator in the Keycloak console (no API for it in phase 00); disabled users keep already-issued access tokens until they expire (≤ 15 min). Assumption: acceptable for the pilot (ADR-016).
- 2026-11-22 — Location scope of role grants is stored and returned but not yet enforced; enforcement belongs to the handlers of location-bound modules (inventory, sales) from phase 02.
- 2026-11-22 — Personal Data Protection Act legal review is pending (ADR-017): lawful basis, retention per category, IP addresses in the audit trail, hosting region, breach procedure, processor agreement. Assumption: conservative defaults of ADR-017.
- 2026-11-22 — Spring Boot Docker Compose support (optional in T00-10) is not used: the stack is started explicitly, which keeps `bootRun` independent of Docker and avoids starting Keycloak from the app.
- 2026-11-22 — Phase review items deferred (no rule broken today; each needs design or volume first): idempotency records stuck `IN_PROGRESS` after a crash need a lease and recovery; API audit records should move to the outbox (today: synchronous, counted by `bmp.audit.lost` when lost); reject tenant-user tokens without an ACTIVE iam profile (closes the 15-minute window of disabled users); an allow-list of user clients (`azp`) besides the platform one; Keycloak fine-grained admin permissions for `bmp-admin`; key order of `iam.role_permission`/`user_role` (tenant_id first) and the globally unique `idp_subject`/`idp_client_id`; GitHub Actions pinned by commit SHA and image signing by digest; error categories of `METHOD_NOT_ALLOWED`/`UNAUTHENTICATED`; tenant defaults (LKR, Asia/Colombo) as configuration.
- 2026-11-22 — Currencies without two decimals (JPY, KWD, ...) are refused at tenant creation and in settings until money rounds to each currency's minor units.
- 2026-11-22 — Production migrations run as a separate job with `bmp.persistence.migrate-on-startup=true` and the migrator credentials; the app itself defaults to false (06 §1). The job itself arrives with the Helm charts (phase 06).
- 2026-11-22 — The seeded MANAGER role has `pricing:write` but not `pricing:read` (08-security.md §3), so managers may change prices but not list price lists or resolve baskets. Assumption: kept as specified; tenants grant `pricing:read` through the iam role API (ADR-021). Confirm with the client.
- 2026-11-23 — The seeded MANAGER role has no `party:` permission (08-security.md §3) although PTY-02 says "a manager" may force a duplicate phone. Assumption: kept as specified; tenants grant `party:write` through the iam role API (ADR-022).
- 2026-11-23 — The seeded STOREKEEPER role has `procurement:receive` but not `procurement:read` (08-security.md §3): storekeepers create and post receipts but cannot list or open them, and cannot send goods back (`procurement:correct`). Assumption: kept as specified; tenants grant more through the iam role API (ADR-023).
- 2026-11-23 — Phones without a country code are read as Sri Lankan (+94) per PTY-01; the tenant module has no country setting. Assumption: a constant until a tenant setting is needed (ADR-022).
- 2026-11-22 — A price applies from its `minQty` (default 1, PRC-02/PRC-04), so 0.75 kg of loose goods needs a kilogram price with a smaller minimum (the pilot fixture uses 0.001). Assumption: kept as specified; confirm whether weighed units should default lower (ADR-021 item 10).
- 2026-11-22 — Pilot fixture prices, the 18 % VAT rate on cooking oil only and the oil density 1086.96 ml/kg are placeholders (`PilotShopFixture` Javadoc); the client's figures are still open (§9 below).
- 2026-11-22 — Pricing has no `api` query interface yet; sales (phase 02) decides between calling `prices:resolve` in-process through a new pricing API and its own replica (see `modules/pricing/README.md`).
- 2026-11-22 — Phase 01 review items deferred (no rule broken today; each needs design, a later module or volume first):
  - Till caches (phase 03, sync): deactivating or reprioritising a price list publishes no event and keeps the snapshot ETag; price rules have no versions or events, so a bill's `RULE:<code>` cannot show what the rule did then and tills cannot price offline with rules.
  - Bulk price upserts report the first bad row only, without its index (04 §4 asks for all field errors as `prices[i].…`).
  - Key order: these constraints do not start with `tenant_id` (06 §3), deferred like the iam ones: pricing `uk_price_list_unit_min_qty_from`, `pk_price_list_version`, `pk_variant_price_ref`; catalog `pk_product_conversion`, `uk_attribute_type_product_code`, `uk_attribute_value_type_code`, `pk_variant_exclusion`, `pk_variant_attribute_value`, `uk_packaging_unit_variant_code`/`_base`, `pk_transformation_definition_version`.
  - Tests: no query-count assertions on list endpoints (02 §9) and no IT of two parallel upserts on one list (09 §2); the inbox test of the variant listener cannot tell a missing inbox check from the version guard.
  - Logging: no INFO business-event lines yet for "price list version published" or "product created" (05 §3).
  - `prices:resolve` trusts `customerGroupId` from the caller; derive it from the customer once the party module exists.
  - Resolution rounds with the platform default policy, not the tenant's `sales.rounding`, and dates taxes by calendar day in the tenant zone, not by business day (`businessDayCutoffHour`); align when sales (phase 03) fixes the tax point.
  - PATCH cannot clear optional fields (product category/tax category, rule validity, tax rate end); the catalog accepts inactive tax categories.
- 2026-11-21 — Sinhala (`si`) and Tamil (`ta`) message bundles are English placeholders (`i18n/*/messages_si|ta.properties`); translations needed before go-live.

Known questions from the client (Requirements Understanding Document §9), to be configured, not coded:
- Measured yield: 750 ml bottles per 18 kg can of oil (sets the oil conversion factor).
- Eggs counted singly or by tray; tray size.
- Wholesale price by quantity threshold, by customer, or both.
- Soya meat brands and packet sizes; own packet sizes for loose soya and dried fish.
- Credit customers and limits.
- Payment methods accepted.
- Printer make, model, paper width, connection; sample bill.
- Taxes shown on the bill.
- Number of users, who may change prices, number of billing counters.

## Phase summaries

_Filled in when each phase closes: what was built, ADRs, limitations._

### Phase 00 — Foundation (2026-11-22)

**Built.** A Gradle 9 multi-project build (convention plugins, pinned catalog, Error Prone + NullAway, Spotless,
JaCoCo gates, PIT wiring, contract-first OpenAPI generation) for a Spring Boot 4.2 / Java 25 modular monolith:
- `libs/shared-kernel`: Money/Quantity/Allocation, typed UUIDv7 ids, Tenant/Actor contexts (ScopedValue), error
  hierarchy, domain event envelope, BusinessDate, permission patterns, `@Sensitive`.
- `libs/web-support`: Problem Details with stable codes and i18n, request ids, access log, size limits, idempotency
  (JDBC store, replay, redaction of one-time secrets), ETag/If-Match, keyset cursors, JWT resource server with tenant
  resolution, platform-admin acting via `X-Tenant-Id`, permission evaluation, ECS logging with masking.
- `libs/persistence-support`: per-module Flyway, fail-closed RLS with a transaction-scoped tenant (ADR-013), JPA
  auditing, Modulith JDBC outbox with tenant-bound listeners, inbox, failed-event store and resubmission, number
  sequences (ADR-012), backlog health and gauge, ShedLock.
- `libs/test-support`: PostgreSQL 18 Testcontainers with the production role model, JWT test tokens, contract and
  event-contract validation, RLS coverage checker, ArchUnit rule sets.
- Modules: **tenant** (tenants, locations, versioned settings with JSON schemas, receipt templates; TEN-01..04),
  **iam** (users, roles, devices, Keycloak gateway with resilience and compensation; IAM-01..03; ADR-016), **audit**
  (hash-chained append-only trail of API writes and all domain events).
- App: actuator on a management port, OTLP tracing, Prometheus/OTLP metrics, Modulith verification and ArchUnit
  tests, local seed data. Local stack (`deploy/local`), `.http` collections, README, GitHub Actions pipeline.

**Verified.** `./gradlew build` green (all gates, unit + Testcontainers ITs incl. Keycloak 26.8.2). Locally:
compose + `bootRun` start cleanly, health UP; with tokens from local Keycloak: location created, settings read and
updated, user created (invitation in Mailpit), device registered, its token accepted, revoked and rejected; audit
trail filled; traces and metrics visible in Grafana. Spectral lint clean; Trivy scan of the SBOM clean. A fresh clone
compiles.

**ADRs.** 011 pinned versions · 012 number sequences per module · 013 fail-closed RLS · 014 identity, tenant claim
and permissions · 015 event publication style · 016 tenant roles in the identity provider · 017 personal data
protection (legal review pending).

**Review.** The `code-reviewer` agent found no BLOCKER and 8 MAJOR issues, all fixed in `6161cd8`: roles taken only
from where Keycloak puts them (platform role via allowed clients only, device tokens exactly `DEVICE`); idempotency
bound to the caller and refusals not stored; platform-administrator reads audited; Keycloak accounts created disabled
until their profile is stored; TEN-02 evidence so confirmed documents lock everyone; contract-driven 401/403 checks for
every operation; backlog out of readiness; a grant ceiling against privilege escalation. Most MINOR items were fixed
too (TEN-01 and default-template races, 4xx mapping, purge of completed events, stricter RLS check, erasable audit
network data, owner-free runtime, local credentials outside the jar); the rest are listed under open questions.

**Limitations / carried forward.**
- CI runs on GitHub (`Dlahiru41/business-manager`). Maven Central rate-limits fresh runners (HTTP 429); downloads retry six times, and SBOM metadata lookups are off. A dependency proxy or a shared Gradle cache would make this robust as the build grows.
- Location scope of role grants is stored, not enforced (phase 02 handlers). Permission-sensitive reads are not
  audited automatically. Disabled users keep issued access tokens until expiry (≤ 15 min).
- Security filter-chain spans appear as separate trace roots next to the HTTP request span; to be tidied with the
  observability work of phase 06.
- Failed-event administration is a job and a store, without an admin endpoint yet.
- `si`/`ta` messages are English placeholders.

### Phase 01 — Catalog and pricing (2026-11-22)

**Built.**
- `catalog`: dynamic product model with templates (SIMPLE, VARIANT_MATRIX, BULK_BREAK, BRANDED_PACKET, LOOSE_REPACK,
  RECIPE, SERVICE), units of measure (standard + tenant), product conversions and a unit converter, variant matrix
  generation with exclusions and SKU generation, packaging units with stock states and barcodes, transformation
  definitions with frozen published versions, categories, catalog snapshots for tills (ETag/304) and full snapshot
  events (CAT-01..CAT-14). Schema `catalog` under RLS; per-tenant `catalog_revision` orders till deltas.
- `pricing`: price lists (Retail/Wholesale seeded per tenant), dated prices with an append-only version ledger and
  change summaries, bulk upsert (≤ 1,000 rows, If-Match, Idempotency-Key), list snapshots, price rules (quantity
  breaks, promotions, markups), tax categories and dated rates, pure basket resolution with line outcomes, list
  version and source (PRC-01..PRC-10). A replica of sellable variants fed by catalog events; the catalog checks tax
  categories through a pricing SPI.
- `tenant`: setting `pricing.priceChangesRequireOwner` (PRC-08) and `TenantQueries.setting/currentTenant`.
- `libs/test-support`: `PilotShopFixture` (the pilot shop through the public API, placeholder prices);
  `app`: `CatalogPricingFlowIT` (mixed retail and wholesale baskets, VAT, the 300-egg break, CAT-14 oil yield).
- Contracts `catalog.yaml`, `pricing.yaml`, AsyncAPI channels; READMEs and `http/catalog.http`, `http/pricing.http`.

**Verified.** `./gradlew build` green with every gate; a named test per CAT/PRC rule; isolation ITs for both modules;
Spectral 6.15.0 clean; AsyncAPI valid; PIT 85 % (`catalog.domain`) and 92 % (`pricing.domain`).

**ADRs.** 019 catalog snapshot events and versions · 020 catalog modelling choices · 021 pricing modelling choices.

**Review.** The `code-reviewer` agent found no BLOCKER and 5 MAJOR issues, all fixed: prices that already applied
could be rewritten (now only the future changes, `PRICE_IN_FORCE`); nested `REQUIRES_NEW` tenant reads on the resolve
path could exhaust the pool; a changed tenant currency made every basket fail; regenerated variants got no template
definitions; the prices ETag could be newer than the page (lost update). Fixed minors: CAT-07 on unit reactivation,
definition line quantities at storage scale, own packets added later (state, REPACK definition, UUIDv7 id),
serialised category moves, 400 for foreign or stale cursors, resolution loads only prices valid at the instant,
tenant predicates on JDBC reads, seeded lists follow `pricesTaxInclusive`. The rest is listed under open questions.

**Limitations / carried forward.**
- Pilot prices, the 18 % VAT on oil and the oil density are placeholders; the client's figures are open (§9).
- Weighed units need a price `minQty` below 1 to sell fractions (ADR-021 item 10); MANAGER lacks `pricing:read`.
- Till caching of list status/priority and of rules, rule history, and query-count/concurrency tests are deferred
  (open questions above).
