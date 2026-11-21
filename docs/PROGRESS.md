# Build progress

Agents update this file at the end of every task and every phase. Keep it short and factual.

## Status

| Phase | Name | Status | Branch | Closed on |
| --- | --- | --- | --- | --- |
| 00 | Foundation | in progress | phase/00-foundation | |
| 01 | Catalog and pricing | not started | | |
| 02 | Inventory and procurement | not started | | |
| 03 | Sales, payments, sync and printing | not started | | |
| 04 | Finance and reporting | not started | | |
| 05 | Multi-business onboarding | not started | | |
| 06 | Scale-out to microservices | not started | | |

Status values: `not started` · `in progress` · `blocked` · `done`.

## Current phase

- Phase: 00 — Foundation
- Current task: T00-10 Local environment and developer experience
- Next step (exact, so a new session can resume): T00-10 — `deploy/local/compose.yaml` (postgres 18 + init script, Keycloak 26.8.2 realm import, mailpit, otel-lgtm + dashboard), `.env.example`, local seed of the pilot and second tenants and their users (iam provisioning API), `http/*.http`, root README.

## Task checklist (current phase)

Phase 00:
- [x] T00-01 Repository and build scaffold
- [x] T00-02 Shared kernel (`libs/shared-kernel`)
- [x] T00-03 Web support (`libs/web-support`) — JDBC `IdempotencyStore` + cleanup job land with T00-04 (they need the tenant transaction hook and the platform-admin role)
- [x] T00-04 Persistence support (`libs/persistence-support`) — ADR-012, ADR-013
- [x] T00-05 Observability baseline — trace in local Grafana checked with the DoD (needs compose)
- [x] T00-06 Security — ADR-014; Keycloak Testcontainers IT and the cross-tenant 404 test need real endpoints and are added with T00-07/T00-08
- [x] T00-07 Tenant module — ADR-012, ADR-015; extra platform endpoints `POST /tenants/{id}/suspend|reactivate` (TEN-04 needs a way to suspend)
- [x] T00-08 IAM module — ADR-016 (tenant roles as the `bmp_roles` user attribute; Keycloak 26.8 needs `kc-admin-api` and a mapper allow-list); `@Idempotent(redact)` keeps device secrets out of replay storage; generator option `containerDefaultToNull` (PATCH can tell "absent" from "empty", fixes receipt-template PATCH clearing omitted JSON fields)
- [x] T00-09 Audit module (skeleton) — per-tenant SHA-256 hash chain with gapless sequence (advisory lock), API-write interceptor, generic `DomainEvent` listener, `GET /api/v1/audit-records`; tamper detection proven in `AuditIT`
- [ ] T00-10 Local environment and developer experience
- [ ] T00-11 Architecture tests and documentation
- [ ] T00-12 CI pipeline
- [ ] Definition of Done

## Open questions

_One line each: date, question, assumption taken, ADR link._

- 2026-11-21 — Settings sanity limits (returnWindowDays ≤ 365, offlineMaxHours ≤ 720, periodLockDays ≤ 31) and numbering prefixes (PO-, GRN-, CN-) are assumptions in the schemas; confirm with the client.
- 2026-11-21 — Default role permissions follow 08-security.md §3 literally, so only OWNER has `tenant:read`/`tenant:*`; MANAGER etc. may need `tenant:read` for settings — assumption: least privilege, tenants extend roles via the iam API (ADR-014).
- 2026-11-22 — Users without e-mail get their first password from an administrator in the Keycloak console (no API for it in phase 00); disabled users keep already-issued access tokens until they expire (≤ 15 min). Assumption: acceptable for the pilot (ADR-016).
- 2026-11-22 — Location scope of role grants is stored and returned but not yet enforced; enforcement belongs to the handlers of location-bound modules (inventory, sales) from phase 02.
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
