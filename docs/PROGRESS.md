# Build progress

Agents update this file at the end of every task and every phase. Keep it short and factual.

## Status

| Phase | Name | Status | Branch | Closed on |
| --- | --- | --- | --- | --- |
| 00 | Foundation | done | phase/00-foundation | 2026-11-22 |
| 01 | Catalog and pricing | in progress | phase/01-catalog-and-pricing | |
| 02 | Inventory and procurement | not started | | |
| 03 | Sales, payments, sync and printing | not started | | |
| 04 | Finance and reporting | not started | | |
| 05 | Multi-business onboarding | not started | | |
| 06 | Scale-out to microservices | not started | | |

Status values: `not started` · `in progress` · `blocked` · `done`.

## Current phase

- Phase: 01 — Catalog and pricing (started 2026-11-22, branch `phase/01-catalog-and-pricing`)
- Current task: Definition of Done
- Next step (exact, so a new session can resume): run the Definition of Done checks, then the `code-reviewer` subagent on `git diff main...HEAD`, with `/run-phase 01`.

## Task checklist (current phase)

Phase 01:
- [x] T01-01 Contracts (`catalog.yaml`, `pricing.yaml`, catalog/pricing events in AsyncAPI; `common.yaml` gains `Money`, `If-None-Match`, `304`) — additions beyond the endpoint table: product/variant `reactivate`, definition `retire` (CAT-11), event `TransformationDefinitionRetired`
- [x] T01-02 Catalog domain model (TDD) — LOOSE_REPACK keeps own packets in a state `PACKED_<CODE>` per packet unit (counted apart from loose stock); BULK_BREAK/LOOSE_REPACK definition lines are sized by the packaging unit so a conversion change resizes them (CAT-14)
- [x] T01-03 Catalog persistence and migrations — category path is a text path of ids (no `ltree` extension needed); tracking flags are columns (filtered on); `catalog_revision` per tenant orders sync deltas; published definition versions frozen in `transformation_definition_version` (append-only); standard units seeded before RLS is forced
- [x] T01-04 Catalog application layer and API — snapshot events built after flush and per-tenant catalog revisions (ADR-019); modelling choices in ADR-020; `include` is a comma-separated string (the contract validator cannot parse form arrays); catalog wired into `app`
- [x] T01-05 Pricing module: replicas and domain — pure `PriceResolver`/`TaxCalculator`/`PriceSchedule`; `variant_price_ref` replica keeps the highest `aggregateVersion`, early deactivations leave a tombstone; Retail/Wholesale seeded on `TenantCreated`; pricing implements the catalog's `TaxCategoryLookup` SPI (ADR-021)
- [x] T01-06 Pricing persistence, API, snapshot — prices never edited once in force (upsert ends the open price), list row lock per upsert, append-only `price_list_version` with change summary; rule conditions as typed columns; lists/rules paged in memory; PRC-08 via tenant setting `pricing.priceChangesRequireOwner` (ADR-021); pricing wired into `app`
- [x] T01-07 Pilot shop fixture (catalog + pricing) and `CatalogPricingFlowIT` — `PilotShopFixture` in `libs/test-support` (public API only, contract-checked, placeholder prices/VAT/density documented); kilogram prices of loose goods start at `minQty` 0.001 so 0.75 kg is priced (ADR-021 item 10)
- [ ] Definition of Done
  - [ ] Every CAT-xx and PRC-xx rule has a named test
  - [ ] Pilot shop fixture and flow IT pass; oil conversion change via the API updates the can's `qtyInBase` in later conversions (CAT-14)
  - [ ] OpenAPI conformance validated in all API tests; Spectral clean
  - [ ] Tenant isolation ITs for catalog and pricing
  - [ ] Module READMEs, `.http` files, AsyncAPI updated
  - [ ] PIT ≥ 70% on `catalog.domain` and `pricing.domain` (aim)
  - [ ] PROGRESS summary written

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
- 2026-11-22 — A price applies from its `minQty` (default 1, PRC-02/PRC-04), so 0.75 kg of loose goods needs a kilogram price with a smaller minimum (the pilot fixture uses 0.001). Assumption: kept as specified; confirm whether weighed units should default lower (ADR-021 item 10).
- 2026-11-22 — Pilot fixture prices, the 18 % VAT rate on cooking oil only and the oil density 1086.96 ml/kg are placeholders (`PilotShopFixture` Javadoc); the client's figures are still open (§9 below).
- 2026-11-22 — Pricing has no `api` query interface yet; sales (phase 02) decides between calling `prices:resolve` in-process through a new pricing API and its own replica (see `modules/pricing/README.md`).
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
