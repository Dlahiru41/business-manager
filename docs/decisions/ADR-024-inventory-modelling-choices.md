# ADR-024: Inventory modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-23
- Phase / task: 02 / T02-03..T02-07

## Context
INV-01..INV-17 fix the ledger, costing, transformations, counts and reorder alerts, but leave storage, the meaning of
some numbers and several edge cases open. Each choice below is the most conservative option that keeps stock and its
value correct and auditable (CLAUDE.md §1.3).

## Decisions
1. **Replicas, not calls.** Inventory keeps `stock_item` (units, states, tracking), `stock_product` (name, category,
   for valuation), `location_ref` and `transformation_def_ref` (one row per published version), fed by catalog and
   tenant events with version-guarded upserts. Stock that physically moved is recorded even for a variant deactivated
   meanwhile (posted goods receipts); new manual movements of inactive variants are refused.
2. **The ledger is partitioned by month** of `occurred_at` (2026–2028 plus a default partition), with row-level
   security on every partition and INSERT/SELECT only for the application role. The RLS coverage test checks the
   partitions and their grants too.
3. **A balance's value is the sum of its movements' costs** (`stock_value = Σ cost_amount`). Negative stock is issued
   at the last known cost and trued up by the next receipt with a CORRECTION movement (quantity 0) under both moving
   average and FIFO, so the identity holds at all times. FIFO layers keep their remaining value, not only quantity.
4. **Rebuilding balances** (INV-16) sets on hand and value to the sums of the balance's ledger rows (CORRECTION rows
   included), so `stock_value = Σ cost_amount` holds whatever happened. Only the moving-average cost and the FIFO
   layers, which are not sums, come from replaying the rows in posting order (recorded time, then id) through the
   tenant's current costing policy. FIFO layers are consumed in creation order (layer id = movement id) both live and
   in the replay, never by the business `receivedAt`, so receipts posted out of date order rebuild identically. The
   rebuild locks the tenant's balances first and reads the ledger afterwards, so postings wait and none is lost.
   Changing the costing method of a tenant with stock is not supported (open question).
5. **The low flag lives on `reorder_policy`** (one alert per crossing, INV-13); setting a policy clears the flag when
   stock is above the new level and otherwise leaves it, so the next out-movement at or below the level alerts. The
   preferred supplier is stored as given (inventory does not depend on party); procurement skips suggestions whose
   supplier is not an active supplier.
6. **Count variance is `counted − system quantity at the line's snapshot`** (INV-12): movements after the snapshot
   are in both the counted stock and the system quantity, so they cancel out. Every time a line is counted (a recount
   included) it takes a new snapshot, so a recount is compared with the stock at the recount. Batch-tracked variants are
   counted per batch (`BATCH_REQUIRED`): their stock is held in batch balances only. Posting locks the count first and
   checks it is SUBMITTED before any stock moves.
7. **Goods receipts** (T02-06) post one RECEIPT movement per line: deliveries at their line total into the named or a
   generated batch (`B<yyyyMMdd>-<suffix>`); returns to the supplier (negative lines) leave at current cost with reason
   `RETURN_TO_SUPPLIER`, so stock value follows the costing policy and the difference to the supplier's credit is a
   price matter for finance. Receipts always post, even below zero, and are never refused: a variant inventory has not
   replicated yet makes the event retry later (`OutOfOrderEventException`, not parked); a missing expiry date of an
   expiry-tracked variant is logged and booked (procurement re-checks it when posting).
8. **Events per source with lines, true-ups included.** `StockReceived` covers goods receipts, opening balances and
   transfers (whose lines only move stock between locations); `StockTransformed` carries its outputs. A receipt,
   transfer or transformation into negative stock produces a CORRECTION movement that changes the stock value; every
   such event carries it in `corrections`, so the event's costs always equal the change in stock value. How finance
   books corrections is an open question for phase 04.
9. **Valuation** sums movement quantities and costs up to `asOf` by occurrence time. Rows group by variant, product,
   category or location; stock of products without a category is valued under the nil UUID. Base quantities are summed
   as they are, which is meaningful by variant and product only.
10. **Pilot oil numbers.** With the placeholder density 1,086.96 ml/kg, an 18 kg can holds 19,565.28 ml, so ten cans
   are 195,652.8 ml and opening a can with 19,400 ml measured gives a yield variance of −165.28 ml (INV-10's −165 is the
   rounded figure).

## Consequences
- Reads are SQL over `stock_balance`, `stock_movement` and the replicas; pilot volumes need no projections beyond the
  balances. A rebuild reads a tenant's whole ledger into memory, which suits the pilot; larger tenants need a
  streaming rebuild per variant (phase 06).
