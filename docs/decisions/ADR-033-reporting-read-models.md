# ADR-033: Reporting read models, their replay and the reports

- Status: accepted
- Date: 2026-11-28
- Phase / task: 04 / T04-05, T04-06

## Context
REP-01 asks for read models built only from events and rebuildable "by replaying events from the publication registry /
module rebuild endpoints". The registry purges completed publications after a retention period (phase 00), so it cannot
be the only source of a rebuild. REP-02 joins each bill line with the cost inventory issued for it, and either event may
arrive first; returns and voids must come off the right day; REP-06 wants the owner dashboard below 300 ms.

## Decisions
1. **Reporting keeps its own event log.** Every listener maps the event to a reporting-owned input record (plain ids,
   decimals and dates), appends it to `reporting.event_log` (unique per event, so redelivery is harmless) and applies it
   to the read models in the same transaction. A rebuild deletes the tenant's facts and summaries and applies the log
   again in recorded order with the same code, so rebuilt and incremental models are equal by construction (tested).
   The log is reporting's data, kept like the facts; the publication registry stays free to purge.
2. **Facts.** `fact_bill` (one per bill: totals as paid, location, channel, customer, cashier, hour, status);
   `fact_sale_line` with one entry per bill line sold, per bill line returned on a credit note and per bill line voided
   (kind SALE, RETURN, VOID; revenue net of discounts and tax, signed, so a sum over entries is what is left); the
   sale half and the cost half of an entry are upserted independently, whichever event comes first. Returns and voids
   are dated on the day they happened, not on the sale's day. `fact_payment` (payments positive, refunds negative),
   `fact_stock_movement` (adjustments and counts with reason and cost), `fact_expense`, `fact_drawer` (reconciled
   drawers), `return_watch` (money a return should refund) and the catalog names (`variant_ref`, `product_ref`).
3. **Daily summary.** `daily_summary` per business date and location (expenses under the nil location) is kept by the
   same code: revenue when the sale half lands, cost when the cost half lands on a line whose date is known (a cost
   that arrives first is added when its sale does), expenses, and the number of bills completed that day.
4. **Order.** A void needs its bill's lines: it waits (`OutOfOrderEventException`) until the sale is applied. Every
   other event is applied as it comes.
5. **Rebuild and listeners do not overlap**: listeners take a shared transaction-scoped advisory lock per tenant, the
   rebuild an exclusive one. The rebuild is a background operation (`REPORTING_REBUILD`).
6. **Freshness (REP-03)** is the time the last event was recorded in the log for the tenant; absent before the first.
7. **Overall profit** is read from `finance.api.LedgerQueries`, aging too; stock valuation from inventory's API;
   unsynced devices from sync's API. Reporting never recomputes accounting.

## Consequences
- The log doubles the storage of the events reporting needs; partitioning by month is a phase 06 matter.
- A rebuild reads a tenant's whole log in one transaction; enough for the pilot, streaming per day later.
