# Domain model and glossary

Binding vocabulary. Use these names in code, APIs, tables and tests.

## 1. Glossary

| Term | Meaning |
| --- | --- |
| **Tenant** | One business using the platform. All data belongs to exactly one tenant. |
| **Location** | A shop, store room or warehouse of a tenant. Stock is held per location. |
| **Register** | A billing counter (till) at a location, bound to one **Device**. |
| **Product** | A general thing the business deals in (Eggs, Cooking oil). Groups variants and shared settings. |
| **Product template** | Preset that configures a product: `SIMPLE`, `VARIANT_MATRIX`, `BULK_BREAK`, `BRANDED_PACKET`, `LOOSE_REPACK`, `RECIPE`, `SERVICE`. |
| **Attribute type / value** | A dimension of variation (Colour, Size, Brand) and its allowed values. |
| **Variant (SKU)** | The specific thing stock, cost and price attach to (Red / Large egg). |
| **Unit of measure (UoM)** | `piece`, `g`, `kg`, `ml`, `l`, `m`... with a dimension (COUNT, MASS, VOLUME, LENGTH, AREA, TIME). |
| **Base unit** | The one unit a variant's stock is stored in. All `qty_base` values use it. |
| **Packaging unit** | A buy and/or sell unit defined as a multiple of the base unit (`tray = 30 piece`, `can = 19,565 ml`, `packet 250 g`). The base unit itself is always a packaging unit with `qtyInBase = 1`. |
| **Product conversion** | Product-specific factor between dimensions (oil: 1 kg = 1,086.96 ml at density 0.92, or a measured yield). |
| **Stock state** | A sub-state of a variant's stock (`DEFAULT`, or `SEALED`/`OPEN` for oil). Each packaging unit draws from one state. |
| **Sell mode** | `PREPACKED` (stock is kept of the packaging unit itself, e.g. filled bottles) or `BREAK_ON_SALE` (selling a unit takes its base quantity from bulk stock, e.g. 750 ml from the open can). |
| **Transformation definition** | Recipe turning input stock into output stock: open can, fill bottle, pack loose fish, break tray, bake buns. |
| **Transformation run** | One execution of a definition with actual quantities; records yield variance. |
| **Stock movement** | Append-only ledger row: + in / − out, in base units, with cost and source document. |
| **Stock balance** | Projection: on-hand per variant + location + state + batch, with average cost. |
| **Batch** | Lot of a delivery, optionally with expiry date. |
| **Reorder level** | Threshold that puts an item on the reorder list. |
| **Price list** | Named set of prices (Retail, Wholesale, customer-specific), versioned. |
| **Price** | Amount for variant + packaging unit + price list, with min quantity and validity. |
| **Price rule** | Quantity break, customer-group rule, promotion or markup. |
| **Channel** | `RETAIL` or `WHOLESALE`; chosen per bill; selects the default price list. |
| **Bill** | A sale document (invoice). Lifecycle `DRAFT → COMPLETED → (PARTIALLY_RETURNED | RETURNED | VOIDED)`. |
| **Credit note** | A return document referencing a bill. |
| **Tender / payment** | How a bill is settled: `CASH`, `CARD`, `BANK_TRANSFER`, `MOBILE_WALLET`, `CREDIT`, `CHEQUE`; several per bill allowed. |
| **Drawer session** | Cash drawer from open (float) to close (count), with expected vs counted cash. |
| **Party** | Customer or supplier. |
| **Receivable / payable** | What a customer owes us / what we owe a supplier. |
| **Journal entry** | Balanced double-entry accounting record (Σ debit = Σ credit). |
| **Business date** | The tenant-local date a transaction belongs to (with a configurable cut-off hour). |

## 2. The product model

```
Product 1 ── * AttributeType 1 ── * AttributeValue
   │ base UoM, template, tracking flags, conversions
   └─ 1 ── * Variant ── * VariantAttributeValue
                │ stockStates[], sellMode
                └─ 1 ── * PackagingUnit (qtyInBase, stockState, canBuy, canSell, barcodes)

TransformationDefinition 1 ── * TransformationLine (direction IN/OUT, variant, stockState, qtyBase, costSharePct)
```

### Templates → defaults

| Template | Defaults applied on create | Example |
| --- | --- | --- |
| `SIMPLE` | One variant, base unit = piece, no attributes | Soap bar |
| `VARIANT_MATRIX` | Attributes required; variants generated as cartesian product minus exclusions | Eggs (colour × size), clothing |
| `BULK_BREAK` | One variant with states `SEALED`/`OPEN`; bulk packaging unit `canBuy`; smaller units `canSell` from `OPEN`; auto-creates an `OPEN` transformation definition; sell mode `BREAK_ON_SALE` | Cooking oil, rice sacks, fabric rolls |
| `BRANDED_PACKET` | Attribute `Brand` (+ optional `Size`); base unit = packet | Branded soya meat |
| `LOOSE_REPACK` | Base unit g (or ml); weighed sale enabled; own packet units; auto-creates `PACK` transformation per packet unit | Loose soya, dried fish, spices |
| `RECIPE` | Outputs made from input variants via a `RECIPE` definition | Bakery items |
| `SERVICE` | No stock tracking | Delivery charge |

Templates only set defaults; everything can be edited afterwards.

### The pilot tenant expressed in the model

| Product | Template | Variants | Base unit | Packaging units | Transformations |
| --- | --- | --- | --- | --- | --- |
| Eggs | VARIANT_MATRIX | 12 = {Red, White} × {XS, S, M, L, XL} + Golden Yellow × {L, XL} | piece | piece (1); tray (30, optional) | Break tray → 30 pieces (only if bought by tray) |
| Cooking oil | BULK_BREAK | 1; states SEALED, OPEN | ml | can 18 kg (19,565 ml placeholder, buy+sell wholesale, SEALED); bottles 1000 / 750 / 375 / 187.5 ml (sell, OPEN) | Open can: 1 can SEALED → n ml OPEN |
| Soya meat (branded) | BRANDED_PACKET | one per brand × packet size | packet | packet | none |
| Soya meat (loose) | LOOSE_REPACK | 1 | g | kg (1000 g); own packets | Pack loose → packet |
| Dried fish (Karola) | LOOSE_REPACK | 1 (or per grade) | g | kg; own 250 g / 500 g packets | Pack loose → packet |

## 3. Key invariants (cross-phase)

- A variant has exactly one base unit; all stock and costs are stored per base unit.
- `PackagingUnit.qtyInBase > 0`; exactly one packaging unit per variant has `qtyInBase = 1` and is the base.
- A packaging unit's `stockState` must be one of the variant's `stockStates`.
- A variant must have at least one `canSell` packaging unit to appear in sales; at least one `canBuy` to appear in purchasing.
- Transformations conserve cost: Σ input cost (+ packing materials) = Σ output cost; quantity variance is recorded, cost is absorbed by outputs.
- Sales are never blocked by stock levels (stock may go negative and is flagged); manual adjustments and transformations may be blocked by the `NegativeStockPolicy`.
- Completed bills, stock movements, payments and journal entries are immutable.
- Every money-affecting event produces a balanced journal entry.

## 4. Business settings (tenant module, per tenant)

| Group | Key | Default |
| --- | --- | --- |
| general | `currency` | LKR |
| general | `timeZone` | Asia/Colombo |
| general | `businessDayCutoffHour` | 0 |
| general | `languages` | [en] |
| inventory | `costingMethod` | MOVING_AVERAGE |
| inventory | `negativeStockPolicy` | ALLOW_AND_FLAG |
| inventory | `defaultLocationId` | (first location) |
| sales | `maxCashierDiscountPct` | 5.00 |
| sales | `returnWindowDays` | 7 |
| sales | `voidRequiresManager` | true |
| sales | `allowCreditSales` | false |
| sales | `offlineMaxHours` | 72 |
| sales | `rounding` | LINE_HALF_UP_2 |
| sales | `wholesaleRequiresCustomer` | false (SAL-14) |
| sales | `tenderReferenceRequired` | true (SAL-06: CARD/TRANSFER need a reference) |
| pricing | `pricesTaxInclusive` | true |
| pricing | `derivePriceFromBase` | false |
| pricing | `cashierCanOverridePrice` | false |
| tax | `taxRegistrationNumber` | null |
| finance | `periodLockDays` | 5 |
| payments | `drawerVarianceAlertAmount` | 500.00 |
