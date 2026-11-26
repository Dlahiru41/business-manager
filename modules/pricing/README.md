# Pricing module

Price lists, dated prices per variant and packaging unit, price rules, tax categories with dated rates, and basket
price resolution (phase 01, rules PRC-01..PRC-10). Nothing is specific to one business: lists, rules and taxes are
data. Contract: [`contracts/openapi/pricing.yaml`](../../contracts/openapi/pricing.yaml).

## Aggregates

| Aggregate | Parts | Invariants |
| --- | --- | --- |
| `PriceList` | — | code unique per tenant; scope `ANY`, `CHANNEL`, `CUSTOMER_GROUP` or `CUSTOMER` with exactly the matching target (PRC-01); version grows by one on every price change |
| `Price` | — | amount ≥ 0 in the list's currency; validity `[validFrom, validTo)`; at most one price per list, variant, unit and minimum quantity at any instant (PRC-02, PRC-03) |
| `PriceRule` | typed conditions, effect | percent effects within 0..100; validity `[validFrom, validTo)`; code unique per tenant (PRC-06) |
| `TaxCategory` | — | code unique per tenant |
| `TaxRate` | — | 0..100 %; validity by date; rates with the same code in one category never overlap (PRC-07) |

Domain services: `PriceSchedule` (plans an upsert: end the open price and start a new one, or update a row that starts
at the same instant; refuses overlaps and duplicates), `PriceResolver` (pure: list choice, quantity breaks, derivation,
rules, taxes, line outcomes), `TaxCalculator` (inclusive back-calculation and exclusive addition, compound rates,
rounding per line).

### Resolution (PRC-04..PRC-07, PRC-10)

1. Candidate lists for the request: customer, then customer group, then channel, then `ANY`; within a tier by
   priority, then code. The first list with a price for the line wins.
2. Within the list: the price valid at `at` with the highest `minQty` ≤ quantity. `minQty` defaults to 1, so a weighed unit
   sold in fractions (0.75 kg) needs a price with a smaller minimum, e.g. 0.001 (ADR-021 item 10).
3. No price in the unit asked: if the tenant setting `pricing.derivePriceFromBase` is on, the base-unit price ×
   `qtyInBase` (`source = DERIVED`); otherwise the line is `PRICE_NOT_FOUND` (PRC-05). Unknown or not sellable units
   are `NOT_SELLABLE`.
4. Active rules whose conditions match, by priority; at most one non-stackable promotion; the line never goes below 0.
5. Taxes of the variant's tax category valid on the day, inclusive or exclusive as the list says.
6. Each line carries the list id and version, the source (`LIST`, `DERIVED` or `RULE:<code>`) and the applied rules
   (PRC-10), so a sale records exactly which prices it used.

## Events

Out: `PriceListChanged` (list id, new version, changed prices) after every upsert.

In (idempotent through the inbox):

| Event | Effect |
| --- | --- |
| `TenantCreated` | seeds the `RETAIL` and `WHOLESALE` lists in the tenant currency |
| `SettingsChanged` (`general.currency`) | lists that never held a price follow the new currency; lists with prices keep theirs and stop pricing baskets (ADR-021 item 11) |
| `VariantCreated`, `VariantUpdated`, `VariantDeactivated` | keep `variant_price_ref`, the module's replica of sellable variants and their units; the highest `aggregateVersion` wins, a deactivation that arrives first leaves a tombstone |

## Public API for other modules

- `api.PricingQueries.quote(BasketQuote)`: the same resolution as `POST /api/v1/prices:resolve`, in-process and
  read-only; sales prices online bills with it (ADR-025 §3). Each `QuotedLine` carries its status, unit price, rule
  discount, tax inclusion, price list and version, source and tax rates.
- Pricing implements the catalog's `TaxCategoryLookup` SPI.

## Endpoints

| Area | Permission |
| --- | --- |
| price lists: list, create, read, update | `pricing:read` / `pricing:write` |
| prices: list (filters `variantId`, `unitId`, `at`), bulk upsert (`PUT`, ≤ 1,000 rows, `If-Match`, `Idempotency-Key`) | `pricing:read` / `pricing:write` |
| list versions and their change summaries; list snapshot (full or `sinceVersion`, ETag/304) | `pricing:read` |
| price rules | `pricing:write` |
| `POST /api/v1/prices:resolve` | `pricing:read` |
| tax categories and tax rates | `pricing:write` |

Examples: [`http/pricing.http`](../../http/pricing.http).

## Persistence

Schema `pricing`; every table under row-level security. `price_list_version` is append-only (the application role
may only select and insert). Upserts take a row lock on the list, so concurrent upserts of one list are serialised and
versions never skip or repeat. Decisions: ADR-021.

## Settings and personal data

Settings group `pricing` (owned by the tenant module): `derivePriceFromBase` (default false), `pricesTaxInclusive`
(default for new lists), `cashierCanOverridePrice` (used by sales, phase 02), `priceChangesRequireOwner` (PRC-08:
when true only owners and platform administrators may create or change price lists, prices and price rules, error
`PRICE_CHANGE_RESTRICTED`). Customer-scoped lists hold a customer id only; no other personal data.
