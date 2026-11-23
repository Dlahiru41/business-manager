# Inventory module

Stock as an append-only ledger in base units with live balances, costing (moving average or FIFO), transformations,
wastage, transfers, counts, reorder alerts and valuation (phase 02, rules INV-01..INV-17). Nothing is specific to one
business: units, states, batches and definitions come from the catalog as data. Contract:
[`contracts/openapi/inventory.yaml`](../../contracts/openapi/inventory.yaml).

## Model

| Part | What it is | Invariants |
| --- | --- | --- |
| `stock_movement` (ledger) | one row per stock change, signed, in base units, with its cost | never updated or deleted; `cost_amount` has the sign of `qty_base`; CORRECTION rows move value only (INV-01, INV-02) |
| `stock_balance` | on hand, reserved, average cost and value per variant, location, state and batch | value = Σ cost of its movements; updated with the movement under row locks taken in key order (INV-01) |
| `cost_layer` | FIFO layers with remaining quantity and value | consumed oldest first (INV-04) |
| `batch` | code, expiry, supplier per variant | unique code per variant; issues pick the earliest expiry (INV-14) |
| `reorder_policy` | level, quantity, preferred supplier, low flag | one alert per crossing (INV-13) |
| `TransformationRun` | inputs, outputs, yield variance | cost conserved: Σ in = Σ out (INV-09, INV-10) |
| `StockCount` | lines with snapshot and counted quantity | OPEN → SUBMITTED → POSTED or CANCELLED; posted counts never change (INV-12) |

Domain services: `StockPlanner` (costs a list of lines against locked balances, flags negative stock, adds true-up
corrections), `CostingPolicy` (`MovingAverageCosting`, `FifoCosting`), `TransformationPlanner`, `ReorderRule`,
`BalanceReplay` (rebuild).

## Rules

- **INV-01..INV-08** every change goes through `StockLedger.post`: locks, FEFO spreading of batch-tracked issues,
  costing, ledger rows, balances, reorder evaluation. Sales and receipts always post; manual adjustments and
  transformations follow `negativeStockPolicy` (`INSUFFICIENT_STOCK`).
- **INV-09, INV-10** transformations issue inputs at current cost and share it over the outputs by `costSharePct`
  (largest remainder).
- **INV-11** wastage reasons; OTHER needs a note.
- **INV-12** counts post COUNT_ADJ movements for `counted − snapshot`.
- **INV-13** `PUT /variants/{id}/reorder-policy`, `GET /stock/reorder-list`, `StockLevelLow`.
- **INV-14** receipts and openings of batch-tracked variants get a batch (named or generated) and need the expiry when
  it is tracked.
- **INV-15** transfers: TRANSFER_OUT and TRANSFER_IN at current cost in one transaction.
- **INV-16** `POST /admin/stock/rebuild-balances` replays the ledger and rewrites balances that differ.
- **INV-17** opening balances once per variant, location and state.

Decisions: [ADR-024](../../docs/decisions/ADR-024-inventory-modelling-choices.md).

## Events

Out (`contracts/asyncapi/events.yaml`): `StockReceived` (goods receipts and openings, with cost corrections),
`StockAdjusted`, `StockTransformed`, `StockCountPosted`, `StockLevelLow`. Transfers publish nothing.

In: catalog `ProductCreated/Updated`, `VariantCreated/Updated/Deactivated`, `TransformationDefinitionPublished/Retired`;
tenant `LocationCreated/Updated`; procurement `GoodsReceived` (one RECEIPT movement per line; returns leave at current
cost). Every listener is inbox-guarded.

## Public API for other modules

- `api.events.*`: the events above.
- Inventory implements procurement's `ReorderSuggestionProvider` (PRO-06) with its reorder list and the last received
  cost per base unit.

## Endpoints

| Area | Permission |
| --- | --- |
| balances, movements, valuation, reorder list, transformation runs | `inventory:read` |
| opening balances, adjustments | `inventory:adjust` |
| transformations | `inventory:transform` |
| transfers | `inventory:transfer` |
| counts | `inventory:count` |
| reorder policy | `inventory:write` |
| rebuild balances | `inventory:admin` |

Examples: [`http/inventory.http`](../../http/inventory.http).

## Persistence

Schema `inventory`: replicas (`stock_item`, `stock_product`, `location_ref`, `transformation_def_ref`), the ledger
`stock_movement` (partitioned by month, INSERT/SELECT only), `stock_balance`, `cost_layer`, `batch`,
`reorder_policy`, `transformation_run`, `stock_count`(+ lines); all under row-level security, partitions included.

## Personal data

None.
