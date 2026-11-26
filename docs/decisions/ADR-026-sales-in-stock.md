# ADR-026: Sales in stock

- Status: accepted
- Date: 2026-11-26
- Phase / task: 03 / T03-04

## Context
T03-04 says a completed bill issues SALE movements, a restocked return comes back at the original issue cost found
through `source_line_id`, and a void reverses the bill's SALE movements. It leaves open where returned stock goes when a
line was issued from several batches, what a void does after a return, and what happens when a return or void arrives
before its sale is booked. Each choice is the most conservative option that keeps stock and its value correct and
auditable (CLAUDE.md §1.3).

## Decisions
1. **One SALE movement per bill line and balance**, at current cost (moving average or FIFO), in the line's stock
   state (INV-05, INV-06), never refused (INV-07). Batch-tracked variants are spread over their batches earliest expiry
   first like every outgoing movement, so a line may have several movements. Movements carry `source_type = SALE`,
   `source_id` = the bill and `source_line_id` = the bill line; they occur at the bill's `completedAt`.
2. **Returns undo the issue.** A restocked line comes back onto the balances its sale took it from, the balance it left
   last first, each up to what is still out of it, at the unit cost it left with (`Σ cost / Σ qty` of its SALE
   movements); the last of a balance takes what is left of its cost, so a fully returned line restores exactly its
   issue cost. Movements: `SALE_RETURN`, `source_id` = the credit note, `source_line_id` = the bill line. Lines not
   restocked move nothing (their cost stays with the sale; reporting shows them). More back than went out cannot come
   through sales; should it, the rest goes onto the balance the line left last, because the goods are physically there.
3. **Voids reverse what is still out**: per bill line and balance, the issued quantity less what returns brought back,
   at its remaining issue cost (`SALE_VOID`, `source_id` = the bill). Sales refuses voids of bills with returns; if one
   came anyway, nothing is put back twice, and a second void puts back nothing.
4. **Order of events.** A return or void whose sale is not booked yet throws `OutOfOrderEventException`: the inbox
   entry rolls back and the publication is retried with back-off, never parked. Reading the bill's movements, locking
   their balances and reading them again makes concurrent returns of one bill see each other.
5. **`StockIssued` per booking** (sale, return or void) with the movements of the bill lines and the true-ups of returns
   into negative stock in `corrections`, so finance can book COGS per bill line (phase 04).
6. **Lookup index**: `ix_stock_movement_tenant_source_line (tenant_id, source_line_id)` finds a line's movements.

## Consequences
- The cost of a bill line is the sum of its movements' costs; returns and voids never revalue stock at today's cost.
- A void after a partial return (impossible through sales) books only the remainder; reporting can rely on
  `Σ SALE + SALE_RETURN + SALE_VOID` per line being what is still out.
