# ADR-025: Sales modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-26
- Phase / task: 03 / T03-02, T03-03

## Context
SAL-01..SAL-15 fix how bills are numbered, priced, totalled, tendered, voided and returned, but leave open who may
approve what, how offline bills find their shift, what the default credit exposure counts, and how sales learns prices,
approvers and customers without depending on modules that depend on it. Each choice below is the most conservative
option that keeps bills correct and auditable (CLAUDE.md §1.3).

## Decisions
1. **Two modes, one aggregate.** `Mode.ONLINE` (the POS API) refuses every breach with its error code; `Mode.OFFLINE`
   (bills synced from a device, T03-08) accepts the bill as the customer already paid it and records each breach as a
   `BillFlag` (`PRICE_MISMATCH`, `TOTALS_MISMATCH`, `DISCOUNT_LIMIT_EXCEEDED`, `CREDIT_LIMIT_EXCEEDED`, `LATE_SYNC`,
   `OFFLINE_SHIFT`), logged at WARN and listed with `GET /bills?flagged=true`.
2. **Approvals.** Online, a caller who holds the permission (`sales:price-override`, `sales:discount-unlimited`,
   `sales:credit-override`, `sales:return-override`) approves their own action. Otherwise the request names
   `approvedBy` and sales asks iam whether that user holds the permission (`iam::api` `IamQueries`, a new read-only
   API); a named user without it is 403 `APPROVER_NOT_ALLOWED`. No PIN or second token is checked in this phase: the
   POS is trusted to have authenticated the approver at the till (open question). A void always needs a named approver
   when `voidRequiresManager` is on, even if the caller is a manager, so voids carry two names.
3. **Prices are asked, not replicated.** Online bills are priced again through `pricing::api` `PricingQueries` (a new
   read-only API over the same resolution as `POST /prices:resolve`) inside the bill's transaction. Replicating price
   lists and rules into sales would duplicate pricing's resolution logic. Variants and customers are replicated
   (`sale_item`, `customer_ref`, version-guarded upserts) because sales needs them offline-safe and on every line.
4. **Rule discounts are separate.** A line stores `ruleDiscount` (from pricing rules such as the wholesale quantity
   break) apart from the cashier's `lineDiscount`; only cashier discounts count towards `maxCashierDiscountPct`
   (SAL-05). Online the rule discount comes from pricing; offline the device's figure is kept.
5. **Offline totals and tenders.** The server recomputes every offline bill from the device's prices; the lines and
   `totals` are its figures. When the till's totals differ they are kept as `deviceTotals`, the tenders are checked
   against their grand total, and `SaleCompleted.totals` (and `SaleVoided.grandTotal`) carry them, because that is
   what the customer paid; a difference above 0.01 flags `TOTALS_MISMATCH`. Returns of such a bill are priced from the
   server's line totals; refunds are capped at what was paid each way (§10), so a return worth more than was paid is
   refused (`REFUND_EXCEEDS_PAID`) and needs a smaller return.
6. **Offline shifts.** An offline bill attaches to the shift it names (same register), else the register's shift
   running at `completedAt`, else the register's synthetic offline shift (one per register, created when first needed,
   flag `OFFLINE_SHIFT`), so every bill has a shift and drawer reconciliation (T03-06) can see what was sold outside a
   shift. The offline shift is never the open shift for online bills and is outside the one-open-shift-per-register
   rule (`uk_shift_open_per_register` ignores it).
7. **Default credit exposure** (SAL-07, until finance replaces it in phase 04): Σ CREDIT tenders of the customer's
   completed and returned bills less CREDIT refunds on their credit notes. Collections are ignored, so the figure is
   never lower than the truth. Credit sales also need the tenant setting `allowCreditSales` (default false).
8. **Numbers.** Bill numbers come from the device (SAL-01); a register keeps the highest sequence it accepted so sync
   can detect gaps (T03-08). Credit-note numbers are taken by the server per tenant (`CN-000001`) from
   `sales.number_sequence`, because returns happen online at the counter.
9. **Append-only where it matters.** Credit notes, their lines and refunds are granted SELECT/INSERT only (the RLS
   coverage test checks it). Bill lines and tenders may be deleted, but the aggregate only does so while the bill is a
   draft; a completed bill changes only through its return bookkeeping (`returned_qty/amount/tax`) and its status.
10. **Return amounts** are the pro-rata share of the line's final total and tax; the return that takes the last of a
   line gets what is left, so a fully returned line refunds exactly what was paid. A return is checked whole before
   the bill changes; a bill line appears once in it. Refunds default to CREDIT for what the customer still owes on the
   bill, then to the bill's only money method, or CASH when it was paid by several. Refunds never exceed what was
   paid each way, earlier credit notes included: CREDIT up to the bill's credit tenders and only to its customer, money
   methods up to its other tenders (`REFUND_EXCEEDS_PAID`), so payments never has a refund it cannot take from a
   payment. Offline returns follow the same rule; a refused one is reported in the sync result.
11. **No card data.** Tender and refund references that contain a 13–19 digit run passing the Luhn check are refused
   (`CARD_DATA_NOT_ALLOWED`) so primary account numbers never reach the database or the logs; terminal authorisation
   codes and bank references are fine.
12. **One tax split.** Inclusive back-calculation and compound rates moved from pricing to the shared kernel
   (`TaxSplit`) so sales recomputes tax exactly as pricing quotes it.

## Consequences
- Sales depends on `tenant`, `iam`, `catalog`, `party` and `pricing` APIs only; payments, inventory and finance learn
  about sales through its events, so no cycle forms.
- Pricing a bill online costs one resolution per request; acceptable at counter volumes, cacheable later.
- Approver checks rely on the POS for the approver's presence; a step-up (PIN or token of the approver) is an open
  question for the POS front-end.
