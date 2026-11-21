# Phase 05 — Multi-business onboarding and platform features

## Goal
Any retail or wholesale business can sign up and start billing within a day with **no code change**: business-type template packs, self-service onboarding, multi-location and transfers, bulk imports, languages, webhooks and plan limits.

## Read before starting
- `CLAUDE.md`, `docs/PROGRESS.md`
- `docs/standards/01` §8 (extension points), `03` §11 (async, webhooks), `07` §6, `08`, `09`
- `docs/reference/domain-model.md` §2 (templates), §4 (settings)

## Scope
New `modules/onboarding` (template packs, sign-up process, bulk import orchestration). It sits **downstream** of tenant, iam, catalog, pricing, party and finance, and calls their `api` commands. The onboarding logic must not live in `tenant` or `catalog`: tenant is upstream of everything, and catalog is upstream of pricing and finance, so either would create a dependency cycle. Also in scope: `modules/tenant` (plans and limits), `modules/notification` (webhooks), cross-module i18n, and location scoping.

Before building, add `*Commands` facades to the `api` packages of catalog, pricing, party, finance and iam where they are missing (e.g. `CatalogCommands.createProduct(...)`). They delegate to the existing handlers.

---

## Rules

| Id | Rule |
| --- | --- |
| ONB-01 | A **template pack** is a versioned YAML resource (`modules/onboarding/src/main/resources/template-packs/<code>/pack.yaml`) describing: units, categories, products with templates/attributes/packaging units, transformation definitions, price lists (empty prices), tax categories, expense categories, default settings, default roles. Validated against a JSON Schema at build time (test). |
| ONB-02 | Packs shipped: `egg-oil-dry-goods` (the pilot shop), `grocery`, `hardware` (cement bags, wire by metre, paint tins by colour × size), `pharmacy` (boxes/strips/tablets, batch + expiry mandatory), `bakery` (recipes), `clothing` (size × colour matrix), `blank`. |
| ONB-03 | Onboarding is a process manager in the `onboarding` module: `SIGNED_UP → TENANT_CREATED → IDENTITY_READY (Keycloak org + owner) → PACK_APPLIED (catalog, pricing, party, finance via their api commands) → READY`. Each step is idempotent and resumable (state table `onboarding.onboarding_process`), and failures are visible at `GET /api/v1/onboarding/{id}`. |
| ONB-04 | Self-service sign-up `POST /api/v1/signup` (public, rate-limited, CAPTCHA token verified via a port, e-mail verification through Keycloak) → starts ONB-03. |
| ONB-05 | Applying a pack to an existing tenant only **adds** missing items (idempotent by codes); never overwrites tenant edits. |
| ONB-06 | Bulk import (`POST /api/v1/imports`, CSV/XLSX, async 202): products+variants+units, opening stock, prices, customers, suppliers. Two phases: validate (report all row errors with row/column/code) → commit (all-or-nothing per file). Max 50,000 rows. Templates downloadable from `GET /api/v1/imports/templates/{type}`. |
| ONB-07 | Plans and limits (`tenant.subscription`): max locations, registers, users, products; enforced at creation with 409 `PLAN_LIMIT_REACHED`; limits are data. (Billing/payment of subscriptions is out of scope.) |
| ONB-08 | Location scoping: users can be restricted to locations (`user_role.location_scope`); every list/query filters by allowed locations; transfers require access to both locations. |
| ONB-09 | Languages: `Accept-Language` en/si/ta for problem titles/details, notification templates and receipts; product names support translations (`name_i18n` jsonb) with fallback to the default language. Replace any English placeholders left from phase 00 in `si`/`ta` bundles (translation strings provided by the client; until then keep placeholders and list them in PROGRESS). |
| ONB-10 | Webhooks: tenants subscribe to event types; deliveries signed (HMAC-SHA256, `Webhook-Signature: t=..,v1=..`), retried with exponential back-off for 24 h, auto-disabled after 3 days of failures with an owner notification; delivery log endpoint. Secrets shown once. |
| ONB-11 | Tenant data export (`POST /api/v1/tenant/exports`, owner only, async): all master data and transactions as CSV in a zip — supports portability and offboarding. |
| ONB-12 | Tenant suspension and closure: suspended → read-only (TEN-04); closed → data retained per retention, logins disabled. |

---

## Tasks

### T05-01 Template pack format, schema and packs (ONB-01, 02, 05)
Write the schema, the pack loader (`TemplatePackApplier` in the onboarding module, applying each part through the owning module's `api` commands), and the seven packs. Test: applying each pack to a fresh tenant yields a consistent catalog (every variant sellable or purchasable as intended, every transformation publishable).

### T05-02 Onboarding process and sign-up (ONB-03, 04)
Process manager, public sign-up endpoint with rate limiting (Bucket4j or Spring Framework's `@ConcurrencyLimit` + a token bucket filter), Keycloak organization creation via `IdentityProviderGateway`, onboarding status endpoint. Failure-injection tests for each step.

### T05-03 Bulk import (ONB-06)
Spring Batch is acceptable here (record ADR) or a hand-written chunked importer; streaming XLSX reading (Apache POI `XSSF` event model or `fastexcel-reader`); validation report as a downloadable file.

### T05-04 Plans and limits (ONB-07), location scoping (ONB-08)

### T05-05 Internationalisation (ONB-09)

### T05-06 Webhooks (ONB-10)
Delivery worker with ShedLock, signing, retry schedule, delivery log, test receiver in tests (MockWebServer or WireMock).

### T05-07 Tenant export, suspension, closure (ONB-11, 12)

### T05-08 Second-business flow test
`HardwareShopOnboardingFlowIT`: sign up a hardware shop with the `hardware` pack, import 200 products from XLSX, receive cement bags, sell wire by the metre and paint by colour/size, close the day — with **no code changes** relative to the pilot shop. Also run `ShopDayFlowIT` for a pilot tenant created from the `egg-oil-dry-goods` pack to prove the pack reproduces the fixture.

---

## Definition of Done
- [ ] Every ONB rule has a named test; both flow ITs pass.
- [ ] A new business type can be added by writing only a pack file (demonstrate with `clothing` in a test).
- [ ] Public endpoints rate-limited and covered by security tests.
- [ ] Contracts, READMEs, `.http` files, PROGRESS summary updated.
