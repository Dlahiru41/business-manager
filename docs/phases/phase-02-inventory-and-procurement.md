# Phase 02 — Inventory, procurement and parties

## Goal
Stock as an append-only ledger with live balances, costing, transformations (open can, pack loose goods), wastage, counts and reorder alerts; purchasing and goods receipts; customers and suppliers. At the end the pilot shop can receive deliveries and see exactly what it has and what it is worth.

## Read before starting
- `CLAUDE.md`, `docs/PROGRESS.md`
- `docs/standards/01`, `02` §5, `04`, `06` (§6 ledgers, §7 transactions), `07`, `09`
- `docs/reference/domain-model.md` §2–3, `data-model.md` (party, inventory, procurement), `event-catalogue.md` §4, `posting-rules.md` (to understand what finance will need from your events)

## Scope
`modules/party`, `modules/inventory`, `modules/procurement`. PIT ≥ 70% on domain packages becomes a mandatory gate from this phase.

---

## Party rules

| Id | Rule |
| --- | --- |
| PTY-01 | A party is CUSTOMER, SUPPLIER or BOTH; display name required; phone normalised to E.164 (default country LK). |
| PTY-02 | Duplicate phone within a tenant → 409 `DUPLICATE_PARTY_PHONE` unless `force=true` by a manager. |
| PTY-03 | Credit policy: `creditAllowed`, `creditLimit ≥ 0`, `paymentTermsDays`; only `party:credit` may change it. |
| PTY-04 | Personal fields are `@Sensitive`; `POST /parties/{id}/anonymise` replaces them (`party:anonymise`), keeping ids. |

## Inventory rules

| Id | Rule |
| --- | --- |
| INV-01 | Every stock change is a `stock_movement` row in base units; balances (`stock_balance`) are updated in the same transaction with row locks in a deterministic order. |
| INV-02 | Movement sign: in > 0, out < 0; `cost_amount` has the same sign as `qty_base`. |
| INV-03 | **Moving average** (default): on receipt, `newAvg = (onHand × avg + qty × unitCost) / (onHand + qty)`; if `onHand ≤ 0` before the receipt, `newAvg = unitCost`. Issues are costed at current `avg`. Scale 6 for unit cost, money rounded per policy. |
| INV-04 | **FIFO** (setting `costingMethod=FIFO`): receipts create cost layers; issues consume oldest layers; negative stock issues use the last known cost and are trued up on the next receipt with a CORRECTION movement. Implement behind `CostingPolicy`. |
| INV-05 | Units: any quantity in a packaging unit converts to base via `qtyInBase`; the movement's `stock_state` comes from the packaging unit. |
| INV-06 | `BREAK_ON_SALE` variants issue from the unit's stock state in base units (750 ml from OPEN); `PREPACKED` units are stocked as their own variant/state if configured that way. |
| INV-07 | **Sales are never blocked** by insufficient stock; the issue proceeds, the balance goes negative, `flags` include `NEGATIVE_STOCK`, a WARN log and metric are emitted. |
| INV-08 | Manual adjustments and transformations follow `negativeStockPolicy`: `ALLOW_AND_FLAG` or `BLOCK` (409 `INSUFFICIENT_STOCK`). |
| INV-09 | **Transformation run**: consume inputs = definition IN lines × runs at current cost; outputs = actual quantities given (default expected); total input cost allocated to outputs by `costSharePct` (largest remainder); yield variance = actual − expected recorded on the run; cost is conserved (Σ in = Σ out). |
| INV-10 | Opening an oil can: 1 can SEALED (19,565 ml base) → actual ml OPEN; e.g. 19,400 ml actual gives yield variance −165 ml and a higher unit cost for the open oil. |
| INV-11 | Wastage reasons: `BREAKAGE`, `SPOILAGE`, `SPILLAGE`, `EXPIRED`, `THEFT`, `DAMAGED`, `OTHER` (+ note required for OTHER); permission `inventory:adjust`. |
| INV-12 | Stock count: OPEN (snapshot system qty per line when the line is first counted) → SUBMITTED → POSTED creates `COUNT_ADJ` movements for variances at current cost; posted counts are immutable. Movements that happen between snapshot and posting are accounted for by computing variance as `counted − (system_at_snapshot + movements_since_snapshot)`. |
| INV-13 | Reorder: after any out-movement, if `available ≤ reorderLevel` and `low_flag=false` → set flag, publish `StockLevelLow`; when it rises above the level → clear the flag. One alert per crossing. |
| INV-14 | Batches/expiry when the product tracks them: receipts require batch (auto-generate if missing) and expiry; issues pick earliest expiry first (FEFO) unless a batch is specified. |
| INV-15 | Transfers between locations: paired TRANSFER_OUT/TRANSFER_IN movements in one transaction at current cost. |
| INV-16 | Projections are rebuildable: `POST /api/v1/admin/stock/rebuild-balances` recomputes from the ledger; an IT asserts equality with incremental results. |
| INV-17 | Opening balances: `POST /stock/opening-balances` (once per variant/location/state) with quantity and unit cost → OPENING movement. |

## Procurement rules

| Id | Rule |
| --- | --- |
| PRO-01 | Purchase orders: DRAFT → SENT → PARTIALLY_RECEIVED/RECEIVED or CANCELLED; lines use `canBuy` units only (422 `UNIT_NOT_PURCHASABLE`). |
| PRO-02 | Goods receipts can reference a PO or not (walk-in delivery). Posting is a separate action; posted receipts are immutable (corrections = return-to-supplier receipt with negative lines, permission `procurement:correct`). |
| PRO-03 | Receipt line: `qtyBase = qty × qtyInBase`, `unitCostBase = unitCost / qtyInBase` (scale 6). Egg example: 300 small, 300 medium, 260 large in one receipt. |
| PRO-04 | Posting publishes `GoodsReceived`; inventory listener creates RECEIPT movements. |
| PRO-05 | Supplier invoices match one or more posted receipts; difference between invoice and receipt value is a price variance carried on the event. |
| PRO-06 | `POST /purchase-orders:from-reorder-list` builds draft POs grouped by preferred supplier from the current reorder list. Procurement is upstream of inventory, so it declares the SPI `procurement.api.spi.ReorderSuggestionProvider`. Inventory implements it, and procurement's default implementation returns an empty list. Procurement must not import inventory. |
| PRO-07 | Numbers: `PO-000001`, `GRN-000001` per tenant via the module's `number_sequence`. |

---

## Tasks

### T02-01 Contracts
`party.yaml`, `inventory.yaml`, `procurement.yaml`, events in AsyncAPI.

Party endpoints: `GET/POST /api/v1/parties`, `GET/PATCH /api/v1/parties/{id}`, `PUT /api/v1/parties/{id}/credit-policy`, `POST /api/v1/parties/{id}/anonymise`, `GET /api/v1/parties:search?q=&type=` (fast, prefix on name/phone), `GET/POST/PATCH /api/v1/customer-groups`.

Inventory endpoints:

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/api/v1/stock/balances?variantId=&locationId=&state=&belowReorder=&negative=` | `inventory:read` |
| GET | `/api/v1/stock/movements?variantId=&locationId=&type=&from=&to=` | `inventory:read` |
| POST | `/api/v1/stock/opening-balances` | `inventory:adjust` |
| POST | `/api/v1/stock/transformations` | `inventory:transform` |
| GET | `/api/v1/stock/transformations[/{id}]` | `inventory:read` |
| POST | `/api/v1/stock/adjustments` | `inventory:adjust` |
| POST | `/api/v1/stock/transfers` | `inventory:transfer` |
| GET / POST | `/api/v1/stock/counts`, `PUT /{id}/lines`, `POST /{id}/submit`, `POST /{id}/post`, `POST /{id}/cancel` | `inventory:count` |
| GET | `/api/v1/stock/reorder-list?locationId=` | `inventory:read` |
| PUT | `/api/v1/variants/{id}/reorder-policy` | `inventory:write` |
| GET | `/api/v1/stock/valuation?asOf=&groupBy=variant|product|category|location` | `inventory:read` |
| POST | `/api/v1/admin/stock/rebuild-balances` | `platform:admin` or `inventory:admin` |

Procurement endpoints: `GET/POST/PATCH /api/v1/purchase-orders[/{id}]`, `POST /{id}/send`, `POST /{id}/cancel`, `POST /api/v1/purchase-orders:from-reorder-list`; `GET/POST/PATCH /api/v1/goods-receipts[/{id}]`, `POST /{id}/post`; `GET/POST /api/v1/supplier-invoices[/{id}]`, `POST /{id}/post`.

### T02-02 Party module
Domain, persistence, API, events (`PartyCreated/Updated`), search index (`pg_trgm` index on name, btree on phone), tests for PTY rules.

### T02-03 Inventory replicas
Listeners: catalog `ProductCreated/Updated`, `VariantCreated/Updated/Deactivated` → `stock_item`; `TransformationDefinitionPublished` → `transformation_def_ref`; tenant `LocationCreated` → `location_ref`. Inbox-guarded, tests for idempotency.

### T02-04 Inventory ledger core (TDD first)
- `StockLedger` application service: `post(List<MovementRequest>)` → validates, converts units, orders locks, inserts movements (batch), updates balances, applies `CostingPolicy`, evaluates reorder (INV-13), returns posted movements with costs.
- `CostingPolicy` implementations: `MovingAverageCosting`, `FifoCosting`.
- `NegativeStockPolicy`.
- Exhaustive unit tests (INV-01..08, 13, 14) and a concurrency IT: 50 concurrent issues of 1 unit on the same balance from 50 virtual threads → final on-hand exact, no deadlocks.

### T02-05 Transformations, adjustments, transfers, opening balances, counts
Handlers and APIs for INV-09..12, 15, 17 with tests, including the oil example (INV-10) with exact expected numbers and a dried-fish repack (1,000 g loose → 4 × 250 g packets, cost conserved).

### T02-06 Inventory listeners for purchasing
`GoodsReceived` → receipt movements → `StockReceived`. (Sales listeners come in phase 03.)

### T02-07 Valuation, reorder list, rebuild
Queries with `JdbcClient` projections; rebuild command and equality IT (INV-16).

### T02-08 Procurement module
Domain (PO, GoodsReceipt, SupplierInvoice), numbering, APIs, events (`GoodsReceived`, `SupplierInvoicePosted`), `from-reorder-list` via the `ReorderSuggestionProvider` SPI (implemented in inventory), and tests for PRO rules. Build procurement **before** the inventory listener for `GoodsReceived` if the task order makes that easier; the dependency direction is procurement → inventory listens.

### T02-09 Flow test with the pilot shop
`ReceiveAndTransformFlowIT` using `PilotShopFixture`:
1. Create supplier; receive 300 small, 300 medium, 260 large red eggs at different costs → balances and average costs correct.
2. Receive 10 oil cans at 13,800.00 each → 10 cans SEALED (195,650 ml base).
3. Open 1 can with actual 19,400 ml → SEALED −19,565 ml, OPEN +19,400 ml, cost conserved, unit cost of OPEN oil = 13,800 / 19,400.
4. Pack 1 kg loose Karola into 4 × 250 g packets.
5. Record 6 broken eggs (BREAKAGE) → `StockAdjusted` with cost.
6. Set reorder level for large red eggs and cross it → exactly one `StockLevelLow`.
7. Valuation report totals equal Σ balances × avg cost.

---

## Definition of Done
- [ ] Every PTY/INV/PRO rule has a named test; flow IT passes with exact numbers.
- [ ] Ledger tables have no UPDATE/DELETE grants (RLS coverage test extended to check grants).
- [ ] Concurrency IT and rebuild-equality IT pass.
- [ ] PIT ≥ 70% on `party.domain`, `inventory.domain`, `procurement.domain`.
- [ ] Contracts, READMEs, `.http` files updated; PROGRESS summary written.
