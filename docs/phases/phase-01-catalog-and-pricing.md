# Phase 01 — Catalog and pricing

## Goal
The dynamic product model and price resolution. At the end, the pilot shop's four product lines (and any other business's products) can be created entirely through the API, with retail and wholesale prices resolved for any variant, unit, customer and quantity.

## Read before starting
- `CLAUDE.md`, `docs/PROGRESS.md`
- `docs/standards/01`, `02` (§5 money), `03`, `04`, `06`, `07`, `09`
- `docs/reference/domain-model.md` (all), `docs/reference/data-model.md` (catalog, pricing), `docs/reference/event-catalogue.md`

## Scope
`modules/catalog`, `modules/pricing`, test fixtures for the pilot shop (catalog part).

---

## Catalog business rules

| Id | Rule |
| --- | --- |
| CAT-01 | Platform-standard units are seeded (piece, g, kg, ml, l, m, cm, pack, dozen) with dimensions and SI factors; tenants may add custom units. |
| CAT-02 | Every product has exactly one base unit; on product creation a base packaging unit (`is_base=true`, `qtyInBase=1`) is created for each variant. |
| CAT-03 | Converting between units of the same dimension uses SI factors; across dimensions only through a `product_conversion` (else 422 `UNIT_NOT_CONVERTIBLE`). |
| CAT-04 | `qtyInBase` > 0; may be fractional (187.5 ml). Packaging unit codes unique per variant. |
| CAT-05 | Variant matrix generation = cartesian product of selected attribute values minus exclusions; regeneration adds missing combinations only and never deletes existing variants (it deactivates combinations newly excluded, if they have no stock — stock check comes from inventory later; for now deactivate). |
| CAT-06 | SKU auto-generation: `<PRODUCTCODE>-<ATTR1CODE>-<ATTR2CODE>` uppercased, de-duplicated with a numeric suffix; may be overridden; unique per tenant (409 `SKU_ALREADY_EXISTS`). |
| CAT-07 | A packaging unit's `stockState` must be in the variant's `stockStates` (422 `INVALID_STOCK_STATE`). |
| CAT-08 | A variant needs ≥ 1 `canSell` unit to be sellable and ≥ 1 `canBuy` unit to be purchasable; flags exposed as `sellable`/`purchasable` in responses. |
| CAT-09 | Templates apply defaults from `domain-model.md` §2; `BULK_BREAK` auto-creates an `OPEN` transformation definition (1 bulk unit SEALED → qtyInBase OPEN); `LOOSE_REPACK` auto-creates a `REPACK` definition per packet unit (packet qtyInBase loose → 1 packet). |
| CAT-10 | Transformation definitions: ≥ 1 IN and ≥ 1 OUT line; all variants of the same tenant; OUT `costSharePct` sum = 100 (single OUT defaults to 100); publishing increments `defVersion` and makes it immutable; edits create a new draft version. |
| CAT-11 | Deactivation, never deletion, for products, variants, units, definitions once published. |
| CAT-12 | Barcodes unique per tenant; one barcode maps to exactly one packaging unit. |
| CAT-13 | Product changes publish `ProductUpdated` / `VariantUpdated` with full packaging-unit snapshots (consumers keep replicas). |
| CAT-14 | Product conversion factors can be updated (e.g. measured oil yield); this changes future conversions only and publishes `VariantUpdated`. |

## Pricing business rules

| Id | Rule |
| --- | --- |
| PRC-01 | Price lists have a channel (RETAIL/WHOLESALE/ANY) and optional customer group or customer. Seed `Retail` and `Wholesale` lists on `TenantCreated`. |
| PRC-02 | A price is for variant + packaging unit + list, with `minQty` (default 1) and validity window. Overlapping validity for the same key → 409 `PRICE_OVERLAP`. |
| PRC-03 | Any price change in a list increments the list's version (`price_list_version` row with change summary) and publishes `PriceListChanged`. |
| PRC-04 | **Resolution order** for (channel, customer?, variant, unit, qty, at): candidate lists = customer-specific → customer-group → channel → ANY, each ordered by priority; take the first list that has a valid price for the unit; within it pick the row with the highest `minQty ≤ qty`. |
| PRC-05 | If no price exists for the unit and `pricing.derivePriceFromBase=true`, derive = base-unit price × `qtyInBase` (rounded per policy) and mark `source=DERIVED`; else return `PRICE_NOT_FOUND` for that line (not an exception for the whole basket). |
| PRC-06 | Price rules apply after list price, ordered by priority; at most one PROMOTION per line unless the rule is `stackable`; result never below zero. |
| PRC-07 | Tax: each variant's tax category (from catalog replica) → active rates at `at`; tax-inclusive lists back-calculate tax: `tax = gross × rate / (100 + rate)`; exclusive add on top. Round per line. |
| PRC-08 | Changing prices requires `pricing:write`; tenant setting may restrict it to OWNER (from the client's question 11). |
| PRC-09 | Snapshot endpoint returns a complete list for a version, or deltas since a version, for POS caching. |
| PRC-10 | Resolution responses include `priceListId`, `priceListVersion`, `source` (`LIST`, `DERIVED`, `RULE:<code>`) per line for audit on the bill. |

---

## Tasks

### T01-01 Contracts
- `contracts/openapi/catalog.yaml` and `pricing.yaml` with all endpoints below, examples using the pilot shop (eggs matrix, oil bulk-break, soya branded, karola loose-repack), every error code.
- Add catalog/pricing events to `contracts/asyncapi/events.yaml`.

Catalog endpoints:

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/api/v1/product-templates` | `catalog:read` |
| GET / POST | `/api/v1/units` | `catalog:read` / `catalog:write` |
| GET / POST / PATCH | `/api/v1/categories[/{id}]` | |
| POST | `/api/v1/products` | `catalog:write` — create from template with attributes, variants/matrix, packaging units in one call (`@Idempotent`) |
| GET | `/api/v1/products` (filters: q, categoryId, status, template) / `/{id}?include=variants,units` | `catalog:read` |
| PATCH | `/api/v1/products/{id}` (If-Match) | `catalog:write` |
| POST | `/api/v1/products/{id}/deactivate` | `catalog:write` |
| GET / POST / PATCH | `/api/v1/products/{id}/attributes[/{attrId}]` (values nested) | |
| PUT | `/api/v1/products/{id}/conversions` | `catalog:write` |
| POST | `/api/v1/products/{id}/variants:generate` (body: exclusions, dryRun) | `catalog:write` |
| GET / POST / PATCH | `/api/v1/products/{id}/variants[/{variantId}]` | |
| POST | `/api/v1/variants/{id}/deactivate` | |
| GET / POST / PATCH | `/api/v1/variants/{id}/packaging-units[/{unitId}]` | |
| POST / DELETE | `/api/v1/packaging-units/{id}/barcodes[/{code}]` | |
| GET | `/api/v1/variants:lookup?barcode=` / `?sku=` | `catalog:read` |
| GET | `/api/v1/variants` (filters: sellable, purchasable, q, productId) | `catalog:read` |
| GET / POST / PATCH | `/api/v1/transformation-definitions[/{id}]` | `catalog:write` |
| POST | `/api/v1/transformation-definitions/{id}/publish` | `catalog:write` |
| GET | `/api/v1/catalog/snapshot?sinceVersion=` | `catalog:read` (POS/sync) |

Pricing endpoints:

| Method | Path | Permission |
| --- | --- | --- |
| GET / POST / PATCH | `/api/v1/price-lists[/{id}]` | `pricing:read` / `pricing:write` |
| GET | `/api/v1/price-lists/{id}/prices` (filters: variantId, unitId, at) | `pricing:read` |
| PUT | `/api/v1/price-lists/{id}/prices` (bulk upsert ≤ 1,000 rows, `@Idempotent`) | `pricing:write` |
| GET | `/api/v1/price-lists/{id}/versions` / `/{version}` | `pricing:read` |
| GET | `/api/v1/price-lists/{id}/snapshot?sinceVersion=` | `pricing:read` |
| GET / POST / PATCH | `/api/v1/price-rules[/{id}]` | `pricing:write` |
| POST | `/api/v1/prices:resolve` | `pricing:read` |
| GET / POST / PATCH | `/api/v1/tax-categories[/{id}]`, `/api/v1/tax-rates[/{id}]` | `pricing:write` |

### T01-02 Catalog domain model (TDD)
Aggregates: `Product` (with attributes, conversions), `Variant` (with packaging units, barcodes), `TransformationDefinition`, `UnitOfMeasure`, `Category`. Domain services: `VariantMatrixGenerator`, `SkuGenerator`, `UnitConverter`, `ProductTemplateApplier` (strategy per template). Unit tests for every CAT rule.

### T01-03 Catalog persistence and migrations
Tables from `data-model.md` (catalog) with RLS, indexes (`tenant_id, sku`, `tenant_id, product_id`, barcode), constraint → error-code registrations. Seed standard units via migration (tenant_id NULL rows readable by all: RLS policy `tenant_id IS NULL OR tenant_id = current tenant` for this table only).

### T01-04 Catalog application layer and API
Handlers per use case, MapStruct mappers, controllers, `.http` file. `CatalogQueries` in `catalog.api` exposing read-only variant/unit info for other modules (used by sync later). Events per CAT-13.

### T01-05 Pricing module: replicas and domain
- Listener on catalog events → `variant_price_ref` replica (idempotent via inbox).
- Aggregates `PriceList` (with versions), `PriceRule`, `TaxCategory`/`TaxRate`. Domain service `PriceResolver` implementing PRC-04..07 as pure functions over loaded data (no DB in the algorithm) with exhaustive unit tests including tie-breaks and validity boundaries (inclusive from, exclusive to).
- Listener on `TenantCreated` → seed Retail and Wholesale lists.

### T01-06 Pricing persistence, API, snapshot
Migrations, controllers, bulk upsert (validate all rows first, single transaction, one version increment), snapshot/delta endpoint (ETag = version; `304` support with `If-None-Match`).

### T01-07 Pilot shop fixture (catalog + pricing)
`PilotShopFixture` in `libs/test-support` creating the products exactly as in `domain-model.md` §2 through the public API, with placeholder prices (document them as placeholders), and an end-to-end `CatalogPricingFlowIT`:
- Eggs: 12 variants generated; Golden Yellow XS/S/M absent; each has retail and wholesale price; wholesale quantity break at 300 pieces (example rule).
- Oil: base ml; can 19,565 ml (can price wholesale); 4 bottle units sellable from OPEN; OPEN transformation definition auto-created and published.
- Soya branded: 2 brands × 2 sizes = 4 variants, packet base unit.
- Loose soya and Karola: g base, kg unit, 250 g and 500 g packets with REPACK definitions.
- Resolve a mixed basket (retail and wholesale) and assert prices, sources, versions, tax.

---

## Definition of Done
- [ ] Every CAT-xx and PRC-xx rule has a named test.
- [ ] Pilot shop fixture and flow IT pass; changing the oil conversion factor through the API updates the can's `qtyInBase` in later conversions (CAT-14).
- [ ] OpenAPI conformance validated in all API tests; Spectral clean.
- [ ] Tenant isolation ITs for catalog and pricing.
- [ ] Module READMEs, `.http` files, AsyncAPI updated.
- [ ] PIT ≥ 70% on `catalog.domain` and `pricing.domain` (gate becomes mandatory from phase 02; aim now).
- [ ] PROGRESS summary written.
