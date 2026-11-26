# ADR-028: Payments modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-26
- Phase / task: 03 / T03-06, T03-07

## Context
PAY-01..PAY-06 fix what a payment, a refund and a drawer are, but leave open what happens to money that moved without
an open drawer, how a refund is spread over a bill's payments, what a drawer does between its count and its
reconciliation, and who operates a drawer. Each choice below is the most conservative option that keeps money correct
and auditable (CLAUDE.md §1.3).

## Decisions
1. **Ledgers only grow.** `payment`, `refund` and `cash_movement` are INSERT/SELECT only for the application role (the
   RLS coverage test checks it). A payment's `refunded` is the sum of its refunds and its status is always CAPTURED.
   Refunds of one payment are serialised by a transaction-scoped advisory lock (ledger rows cannot be row-locked
   without UPDATE rights), taken in id order.
2. **Money that moved is recorded.** A cash tender or refund that arrives from sales while the register has no drawer
   taking cash is still recorded as a payment or refund, without a cash movement, logged at WARN and counted
   (`bmp.payments.cash_outside_drawer`), so reconciliation sees the gap instead of losing the sale. At the counter
   (API) cash needs an OPEN drawer (`DRAWER_NOT_OPEN`).
3. **Refunds of returns** are taken from the bill's payments, those of the refund's method first (the cash of a mixed
   bill is refunded in cash, as sales defaults to), each up to what is left of it. A refund larger than the payments
   (impossible through sales) is recorded up to them and the rest is logged at WARN and counted
   (`bmp.payments.refunds_unallocated`). The first part keeps the refund id sales gave; further parts and void refunds
   get name-based ids derived from what they refund, so redelivery finds them. CREDIT refunds move no money.
4. **Voids give every payment back in full** by its own method, reason `VOID: <reason>`.
5. **Order of events.** A return or void whose payments are not recorded yet is retried
   (`OutOfOrderEventException`); one whose tenders were all CREDIT moves nothing and is done.
6. **Drawers.** One session per register until it is reconciled (`uk_drawer_session_open_per_register` on OPEN and
   COUNTED). Manual movements need OPEN; sales and refunds keep landing on a COUNTED drawer, so a count can be repeated
   and reconciliation recomputes the variance against the expected cash at that moment. Denominations come from the
   tenant setting `payments.cashDenominations` (LKR notes and coins by default); the same value twice adds up.
7. **Who operates a drawer.** `drawer:operate-own` lets a cashier move cash in and count the drawer they opened; holders
   of `payments:reconcile` operate any drawer (`DRAWER_NOT_YOURS` otherwise). Sync's commands come from the device and
   skip the check.
8. **Variance alerts** are a WARN log and the counter `bmp.payments.drawer_variance_alerts` until the notification
   module exists (phase 04).
9. **Daily totals** use the business date stored with every payment and refund (tenant time zone and cut-off at the
   time), so changing the cut-off later never moves past totals.

## Consequences
- Finance (phase 04) books from `PaymentCaptured`, `PaymentRefunded` and `DrawerReconciled` and never needs to read the
  payments tables.
- A cash sale outside a drawer shows up as a variance when the drawer is reconciled and in the metric; the payment
  itself is complete.
