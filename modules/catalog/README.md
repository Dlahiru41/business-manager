# Catalog module

The dynamic product model (phase 01, rules CAT-01..CAT-14). Products are created from **templates**; their
**variants** (SKUs) carry **packaging units** defined as multiples of one base unit; **transformation definitions**
describe how stock changes form (open a can, repack loose fish). Nothing is specific to one business: every product
rule is data. Contract: [`contracts/openapi/catalog.yaml`](../../contracts/openapi/catalog.yaml).

## Aggregates

| Aggregate | Parts | Invariants |
| --- | --- | --- |
| `Product` | attribute types and values, exclusions, conversions | base unit fixed at creation (CAT-02); attribute types fixed once variants exist; conversions positive, once per unit pair (CAT-03) |
| `Variant` | packaging units, barcodes | one base unit with `qtyInBase = 1` (CAT-02); `qtyInBase > 0`, unit codes unique (CAT-04); units draw from the variant's stock states (CAT-07); barcodes unique per tenant (CAT-12) |
| `TransformationDefinition` | lines | ≥ 1 IN and ≥ 1 OUT, OUT cost shares sum to 100, publishing freezes a version, edits start a new draft (CAT-10) |
| `Category` | — | ≤ 5 levels, no cycles, names unique per level |
| `UnitOfMeasure` | — | standard units shared by all tenants, tenants add their own (CAT-01); immutable |

Domain services: `UnitConverter` (CAT-03, CAT-14), `VariantMatrixGenerator` (CAT-05), `SkuGenerator` (CAT-06),
`ProductTemplateApplier` with one `TemplateStrategy` per template (CAT-09).

### Templates

| Template | Defaults |
| --- | --- |
| `SIMPLE` | one variant, base unit piece |
| `VARIANT_MATRIX` | attributes required; variants = cartesian product minus exclusions |
| `BULK_BREAK` | base ml, states SEALED/OPEN; bought units SEALED, sold units OPEN, sell mode BREAK_ON_SALE; an `OPEN` definition per sealed unit, published |
| `BRANDED_PACKET` | attributes required (Brand, Size); base unit pack |
| `LOOSE_REPACK` | base g, weighed, decimal quantities; sell-only units are own packets kept in their own state `PACKED_<CODE>`; a `REPACK` definition per packet, published |
| `RECIPE` | like SIMPLE; the RECIPE definition is created afterwards |
| `SERVICE` | no stock kept |

## Events out (`contracts/asyncapi/events.yaml`)

`ProductCreated`, `ProductUpdated`, `VariantCreated`, `VariantUpdated`, `VariantDeactivated` (full snapshots with
packaging units and barcodes, CAT-13; consumers keep the highest `aggregateVersion`),
`TransformationDefinitionPublished`, `TransformationDefinitionRetired`.

Events in: none. The catalog depends on no other module; the pricing module implements the
`api.spi.TaxCategoryLookup` SPI so products can only refer to existing tax categories.

## Public API for other modules

`api.CatalogQueries`: variants and packaging units by id, variants by barcode, the catalog version.

## Endpoints

Templates, units, categories, products (create from template, list, read with `include=variants,units`, update,
deactivate/reactivate, conversions, `variants:generate`), attributes, variants (create, update, deactivate/reactivate,
`variants:lookup`, cross-product list with `sellable`/`purchasable`), packaging units and barcodes, transformation
definitions (create, update, publish, retire), and `catalog/snapshot` (full or since a version, ETag/304). Read:
`catalog:read`; write: `catalog:write`. Examples: [`http/catalog.http`](../../http/catalog.http).

## Persistence

Schema `catalog`; every table under row-level security. `unit_of_measure` is the only table whose policy lets rows
without a tenant (the standard units) through. `catalog_revision` holds the tenant's catalog version; every change
takes the next one under a row lock, so till deltas never miss a change. Published definition versions are frozen in
the append-only `transformation_definition_version`. Decisions: ADR-019, ADR-020.

## Settings and personal data

No tenant settings of its own. No personal data.
