# Build progress

Agents update this file at the end of every task and every phase. Keep it short and factual.

## Status

| Phase | Name | Status | Branch | Closed on |
| --- | --- | --- | --- | --- |
| 00 | Foundation | done | phase/00-foundation | 2026-11-22 |
| 01 | Catalog and pricing | done | phase/01-catalog-and-pricing | 2026-11-22 |
| 02 | Inventory and procurement | done | phase/02-inventory-and-procurement | 2026-11-23 |
| 03 | Sales, payments, sync and printing | in progress | phase/03-sales-payments-sync-printing | |
| 04 | Finance and reporting | not started | | |
| 05 | Multi-business onboarding | not started | | |
| 06 | Scale-out to microservices | not started | | |

Status values: `not started` · `in progress` · `blocked` · `done`.

## Current phase

- Phase: 03 — Sales, payments, offline sync and printing (started 2026-11-25, branch
  `phase/03-sales-payments-sync-printing`)
- Current task: T03-11 Full-day flow test
- Next step (exact, so a new session can resume): T03-11 — `app/src/integrationTest/java/com/bmp/ShopDayFlowIT.java`
  with `PilotShopFixture` and a `PosSimulator` (test-support) building offline records: steps 1–9 of the phase file
  (a till device needs the iam device API with the identity provider stubbed in the test); then the Definition of
  Done checks, the code-reviewer subagent on `git diff main...HEAD`, and the phase summary, with `/run-phase 03`.

## Task checklist (current phase)

Phase 03:
- [x] T03-01 Contracts (`sales.yaml`, `payments.yaml`, `sync.yaml`, `documents.yaml`, AsyncAPI events) — online bills only through the sales API (offline ones through `POST /sync/uploads`); `StockIssued` covers SALE, SALE_RETURN and SALE_VOID with signed lines and `corrections`; uploads and receipt rendering keep the Idempotency-Key like every POST; `GET /print-agents/{id}/template` serves the receipt template to agents
- [x] T03-02 Sales domain (TDD): `Bill`, `BillTotalsCalculator`, `DiscountAllocator`, `TenderValidator`, `CreditNote` — pricing's tax split moved to the shared kernel (`TaxSplit`) so bills are taxed exactly as quoted; `CardNumbers` (PAY-05) in the shared kernel; online breaches throw, offline ones become bill flags; offline totals are kept as device totals when they differ, tenders are checked against them
- [x] T03-03 Sales persistence, API, events, registers and shifts (catalog and party replicas) — prices asked of pricing through the new `pricing.api.PricingQueries`, approvers of iam through `iam.api.IamQueries`; offline bills keep their device totals as `deviceTotals` and `SaleCompleted` carries what was paid; the register's synthetic offline shift is outside the one-open-shift rule; two sales settings `wholesaleRequiresCustomer`, `tenderReferenceRequired` (ADR-025)
- [x] T03-04 Inventory listeners for sales (`SaleCompleted`, `SaleReturned`, `SaleVoided` → `StockIssued`) — SALE movements per bill line at current cost; restocked returns undo the issue (balance left last first) at the issue cost; voids put back what is still out; a return or void before its sale is booked retries (ADR-026)
- [x] T03-05 SPIs: credit exposure (SAL-07) and business activity (TEN-02) — sales confirms activity once any bill exists; tenant now keeps the strongest answer of all probes, so a module without documents never unlocks another module's (ADR-027)
- [x] T03-06 Payments module (PAY-01..06, drawer sessions, `PaymentCommands` / `PaymentQueries`) — append-only payment, refund and cash movement ledgers; cash outside a drawer is recorded and flagged, never refused; return refunds spread over the bill's payments by method; drawer count by tenant denominations (`payments.cashDenominations`) and reconciliation against the expected cash at that moment (ADR-028)
- [x] T03-07 Void and return process manager (choreography, partial failure and duplicate delivery tests) — `ReturnChoreographyIT` (app): a return delivered before its sale fails in payments and inventory, succeeds after resubmission, and a duplicate changes nothing (ADR-028 §5)
- [x] T03-08 Sync module (SYN-01..08, command facades, 1,000-record batch < 10 s) — one transaction per record holds the replay check, the effect and the outcome; dataset versions are digests and changed datasets are sent whole; facades `SalesCommands`, `InventoryCommands`/`InventoryQueries` and till read methods in catalog, pricing and party (ADR-029). The 1,000-record batch takes ~100 s on this Windows/Docker Desktop machine (1–3 ms per database round trip): the load test holds 10 s where a 50-record calibration shows a local database, otherwise it checks the batch scales linearly
- [x] T03-09 Receipt renderer (`libs/receipt-renderer`) and documents module — renderer (pure Java + PDFBox): text mode for Latin rows, `GS v 0` raster for other scripts, native `GS ( k` QR, cut, drawer kick after cash; golden files for 58/80 mm text and ESC/POS; documents: render endpoint, print agents, print jobs handed out at least once (re-sent after a minute without an answer), reprints as COPY, template from the tenant's default receipt template; tills get it as the sync dataset `receiptTemplate` through `documents.api.DocumentsQueries`; web-support stores non-text idempotent responses in Base64 so ESC/POS and PDF replay byte for byte (ADR-030)
- [x] T03-10 Print agent application (`print-agent/`) — Spring Boot on 127.0.0.1:9123 behind a shared token (`/v1/print`, `/v1/drawer/open`, `/v1/status`); TCP 9100, OS print queue and serial printers; H2 file queue (Flyway) printed oldest first with back-off, the till's requests never given up; cloud link with the device credential (template cache, long-polled jobs printed once by job id, results resent with the same idempotency key); ECS JSON rolling logs without receipt contents; `jpackage` installers built on CI for Windows (exe) and Linux (deb) (ADR-031)
- [x] T03-11 Full-day flow test (`ShopDayFlowIT`) — the pilot shop's day through the public APIs: shift and drawer, a retail bill with a weighed line, a wholesale bill at the quantity break with card and cash, 20 offline bills from `PosSimulator` (test-support) uploaded twice (20 ACCEPTED, then 20 DUPLICATE; only the over-limit discount flagged), the skipped number reported once, a cash refund with restock, a manager-approved void, the drawer 100.00 short and reconciled; stock by state, a costed SALE movement per line, payments by method and no unfinished event publication at the end. The identity provider is stubbed in the test (device and manager registration)
- [ ] Definition of Done
  - [x] Every SAL/PAY/SYN/DOC rule has a named test (SAL-11 and SAL-13 added: `SalesEdgeCasesIT.sal11…`, `SalesSettingsTest.sal13…`); `ShopDayFlowIT` passes
  - [x] Sync upload is idempotent under retries and partial failures: `SyncResubmissionIT` fails the third bill with a database error, the batch stops with 500, the resent batch stores each bill, payment and stock issue once
  - [x] Print agent prints to the fake printer offline and online (`PrintAgentIT`); `jpackage` app image builds locally — the Windows installer job on CI (`print-agent-installer`) is yet to be seen green
  - [x] Logging check: `ShopDayFlowIT` finds exactly one INFO `Bill completed` line for the credit bill (upload and replay) without the customer's name, phone or id
  - [x] PIT ≥ 70%: sales 86 %, payments 80 %, sync 80 % (new `SyncDomainTest`), documents 94 %, inventory 88 %
  - [x] Contracts, READMEs, `.http` files, PROGRESS summary updated

## Open questions

_One line each: date, question, assumption taken, ADR link._

- 2026-11-26 — SYN load budget: 1,000 records in < 10 s holds only where a database round trip is about 0.25 ms; on Docker Desktop for Windows (2.6 ms) the batch takes ~90 s with one transaction per record (SYN-04). The load test holds 10 s where calibration shows a local database and checks linear scaling elsewhere. Confirm whether 10 s must hold on such machines (would need batched statements or several records per transaction) (ADR-029 §8).
- 2026-11-26 — DOC-03 asks for Noto Sans Sinhala and Noto Sans Tamil to be bundled; downloading them needs the user's approval, so the renderer takes font files by configuration and falls back to installed fonts (Nirmala UI on Windows, `fonts-noto-core` on Linux), and the Sinhala test checks the raster's structure rather than a pixel snapshot. Assumption: bundle the OFL fonts once approved (ADR-030 §1).
- 2026-11-26 — The print agent installs as a per-user application with a menu entry; running it as a Windows service (start before login) is left to whoever sets up the shop PC. Assumption: the shop PC logs in automatically (ADR-031 §8).
- 2026-11-26 — Sync sends a changed dataset whole; row-level deltas (SYN-03 "deltas") are left for large catalogs (ADR-029 §1).
- 2026-11-26 — TEN-02 probes: only sales confirms business activity (bills). Goods receipts, stock openings and purchase orders of a tenant without bills are covered by the default guess only (ASSUMED after one day, platform administrators may still change currency). Assumption: inventory and procurement add probes when needed (ADR-027 §3).
- 2026-11-26 — Approvers named on a bill (`approvedBy`, `discountApprovedBy`, `creditApprovedBy`) are checked for the permission in iam, but their presence at the till is not (no PIN or approver token). Assumption: the POS authenticates the approver locally; a step-up is an open question for the POS front-end (ADR-025 §2).
- 2026-11-26 — Returns of an offline bill flagged `TOTALS_MISMATCH` are priced from the server's line totals, which may exceed what the customer paid. Refunds are now capped at what was paid each way (`REFUND_EXCEEDS_PAID`), so such a return must be smaller; confirm whether a manager should be able to refund the difference (ADR-025 §5, §10).
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
- 2026-11-23 — INV-12 states variance = counted − (system at snapshot + movements since); with the counted quantity taken at the snapshot that would book later movements twice, so variance = counted − system at the (re)count, and every recount takes a new snapshot (ADR-024 §6). Confirm with the client.
- 2026-11-23 — True-ups of negative stock (`corrections` on `StockReceived` and `StockTransformed`) change the stock value; posting-rules.md has no line for them. Assumption: finance (phase 04) books them against COGS; to be decided there (ADR-024 §8).
- 2026-11-23 — Phase 02 review items deferred (no rule broken for the pilot; each needs design or volume first):
  - Costing: changing `costingMethod` with stock on hand is not blocked; FIFO `avgUnitCost` is the last receipt cost after a receipt and the average after an issue.
  - Locks: transfers lock new destination batch keys in a second round and reorder rows are locked per posting, not once per use case; batch-tracked openings of one variant/location/state for different batches can race past INV-17. Deadlocks surface as errors and are retried by clients.
  - Batches: transformation outputs of batch- or expiry-tracked variants go to the no-batch balance; reusing a batch code keeps its first expiry; supplier returns leave by FEFO, not from the delivered batch.
  - Persistence: ledger inserts are row by row (not batched); ledger partitions exist for 2026–2028 only and need a job creating the next ones before 2029 (phase 06); `fk_party_customer_group` leaves out `tenant_id`.
  - API: supplier invoice drafts cannot be cancelled or edited yet but hold their supplier reference; openings, adjustments and transfers return 201 without a Location header; query services return aggregates to controllers in places; the wastage note travels only in `StockAdjusted`.
  - Observability: the negative-stock metric is `bmp.inventory.negative_stock` (05 §6 names `bmp.stock.negative.items`) and, like the business-event INFO logs, is emitted before commit.
  - Party: search applies its limit before ranking prefix matches first; a cashier (`party:create-customer`) may put a new customer into a customer group, which chooses its price list.
- 2026-11-23 — Phones without a country code are read as Sri Lankan (+94) per PTY-01; the tenant module has no country setting. Assumption: a constant until a tenant setting is needed (ADR-022).
- 2026-11-22 — A price applies from its `minQty` (default 1, PRC-02/PRC-04), so 0.75 kg of loose goods needs a kilogram price with a smaller minimum (the pilot fixture uses 0.001). Assumption: kept as specified; confirm whether weighed units should default lower (ADR-021 item 10).
- 2026-11-22 — Pilot fixture prices, the 18 % VAT rate on cooking oil only and the oil density 1086.96 ml/kg are placeholders (`PilotShopFixture` Javadoc); the client's figures are still open (§9 below).
- 2026-11-22 — Pricing has no `api` query interface yet; sales (phase 02) decides between calling `prices:resolve` in-process through a new pricing API and its own replica (see `modules/pricing/README.md`). Resolved 2026-11-26: sales asks `pricing.api.PricingQueries` in-process (ADR-025 §3).
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

### Phase 02 — Inventory, procurement and parties (2026-11-23)

**Built.**
- `party`: customers, suppliers and BOTH with E.164 phones (read in LK), customer groups, credit terms, the
  duplicate-phone rule with `force`, prefix search on name words and phones, anonymisation; events without contact
  details (PTY-01..PTY-04).
- `inventory`: catalog and tenant replicas; an append-only ledger partitioned by month with live balances
  (`stock_value = Σ cost_amount`), moving-average and FIFO costing with CORRECTION true-ups of negative stock, FEFO
  batches, opening balances, wastage, transfers, transformations (oil can opening, karola repack), stock counts with
  recount snapshots, reorder alerts once per crossing, balance/movement/valuation/reorder-list queries, reorder levels
  under If-Match and a ledger-based balance rebuild; books posted goods receipts into stock and provides procurement's
  reorder suggestions (INV-01..INV-17).
- `procurement`: purchase orders, goods receipts (walk-in or against an order, returns as negative receipts that send
  back at most what arrived), supplier invoices with the price variance, per-tenant numbering, orders from the reorder
  list through the `ReorderSuggestionProvider` SPI (PRO-01..PRO-07).
- Contracts `party.yaml`, `inventory.yaml`, `procurement.yaml` and the phase's AsyncAPI events; READMEs and
  `http/party.http`, `http/inventory.http`, `http/procurement.http`.
- `app`: `ReceiveAndTransformFlowIT` runs the pilot shop through procurement and inventory with exact numbers.
- Platform: `OutOfOrderEventException` (retry instead of park), RLS coverage of partitions and their grants, PIT as a
  CI gate for every module (audit raised from 51 % to 97 %).

**Verified.** `./gradlew build` green with every gate; a named test per PTY/INV/PRO rule; isolation and contract ITs
for the three modules; the 50-thread concurrency IT and rebuild-equality ITs (moving average and FIFO); PIT tenant 71,
iam 78, audit 97, catalog 85, pricing 92, party 94, inventory 88, procurement 74 %; Spectral 6.15.0 clean on the
three new OpenAPI contracts and no errors under its AsyncAPI rules for `events.yaml`.

**ADRs.** 022 party modelling choices · 023 procurement modelling choices · 024 inventory modelling choices.

**Review.** The `code-reviewer` agent found no BLOCKER and 6 MAJOR issues, all fixed: a recount booked movements made
after the first count twice (every count now takes a new snapshot); counting a batch-tracked variant without its batch
inflated stock (`BATCH_REQUIRED`); true-ups from transformations and transfers into negative stock were not published
(`corrections` on `StockTransformed`, transfers publish `StockReceived`); the rebuild re-costed issues and FIFO
consumed layers in a different order live and in the replay (on hand and value are now ledger sums, layers follow
creation order); a posted receipt arriving before its variant was parked (now retried); zero quantities and blank
names answered 500 (now validation errors). Fixed minors: count posting checks its state under lock before moving
stock, receipts lock their order when posting, returns only from the delivery's location, procurement re-checks
expiry on post, openings lock all their balances once in key order, indexes on receipt and match lookups. The rest is
listed under open questions.

**Limitations / carried forward.**
- A balance rebuild reads a tenant's whole ledger into memory and orders postings by the app's clock; ledger
  partitions run to 2028.
- Finance must decide how to book `corrections` (phase 04); seeded roles follow 08-security.md §3 literally (MANAGER
  without `party:`, STOREKEEPER without `procurement:read`).
- Deferred review items (locks, batches, metrics, invoice drafts, party search) are listed under open questions.
