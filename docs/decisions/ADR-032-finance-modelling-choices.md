# ADR-032: Finance modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-28
- Phase / task: 04 / T04-01..T04-04

## Context
FIN-01..FIN-11 and `posting-rules.md` fix the chart of accounts, one balanced journal per event, immutability, periods,
open items and the credit SPI. They leave open how some events map to journal lines given what the events really carry
(phases 02–03), how stock value stays equal to the Inventory account, how periods are locked against postings, how
open items settle, and how several edge cases are booked. Each choice is the most conservative option that keeps the
ledger correct and auditable (CLAUDE.md §1.3).

## Decisions
1. **Rules are pure, events are mapped.** `finance.domain.posting` holds one `PostingRule` per event type over
   finance-owned input records; the listeners map each module's event to that record, so the domain imports no other
   module. A rule returns lines by account *key* (a system key or an account id); the writer resolves keys per tenant.
   Zero lines are dropped; a rule with nothing left posts no journal (`StockTransformed` whose costs cancel).
2. **Amounts as the events carry them** (4 decimals for stock costs, `numeric(19,4)`), never re-rounded, so the
   Inventory account equals Σ `stock_value` of inventory to the cent and COGS equals the costs reporting shows.
3. **Sales.** `SaleCompleted`: per bill line Cr SALES `lineTotal + discounts − tax` and Dr SALES_DISCOUNTS
   `lineDiscount + allocatedDiscount` (dimensions variant, channel, location, bill), one Cr TAX_PAYABLE for Σ line tax,
   Dr AR (bill with a customer) or POS_CLEARING (walk-in) for `totals.grandTotal`. When an offline bill's device grand
   total differs from Σ line totals (ADR-025 §5) the difference is a line of its own: Cr SALES when the customer paid
   more, Dr SALES_DISCOUNTS when less, memo `device totals difference`, so the receivable is what was paid.
   Payments, refunds and returns use AR when the event names a customer, POS_CLEARING otherwise. Tender methods map
   to CASH → CASH_DRAWER, CARD → CARD_CLEARING, BANK_TRANSFER and MOBILE_WALLET → TRANSFER_CLEARING,
   CHEQUE → CHEQUES; CREDIT moves no money.
4. **Returns and voids.** `SaleReturned` reverses revenue and tax and credits the receivable; the cost of restocked
   lines comes back through inventory's `StockIssued` (SALE_RETURN), which carries it (lines not restocked keep their
   cost, ADR-026 §2). `SaleVoided` is the exact reversal of the bill's SALE journal (`reverses_id`; retried until that
   journal exists); the money comes back through `PaymentRefunded` (pointing at the payment's journal) and the stock
   through `StockIssued` (SALE_VOID, pointing at the sale's stock journal), each posted once.
5. **Stock.** `StockIssued` Dr COGS / Cr INVENTORY for issues and the reverse for returns and voids. `StockAdjusted`:
   BREAKAGE, SPOILAGE, SPILLAGE, EXPIRED and DAMAGED → WASTAGE; THEFT and OTHER → COUNT_VARIANCE. `StockCountPosted`
   → COUNT_VARIANCE (gain or loss by sign). `StockTransformed`: nothing while Σ input = Σ output cost; a residual goes
   to WASTAGE. `StockReceived`: OPENING_BALANCE → Dr INVENTORY / Cr EQUITY (opening stock is owner's capital);
   TRANSFER lines move value between locations only (no entry); GOODS_RECEIPT lines are booked by `GoodsReceived`, but
   a return to the supplier leaves stock at current cost (ADR-024 §7), so the difference to the receipt's line total
   goes to PPV (finance keeps the receipt's line totals; the stock event is retried until it has them). **True-ups**
   (`corrections` of any inventory event, ADR-024 §8) go to COGS: they correct the cost of stock issued below zero.
6. **Purchases.** `GoodsReceived` Dr INVENTORY / Cr GRNI Σ line totals (negative lines reverse it).
   `SupplierInvoicePosted` Dr GRNI `receiptValue`, PPV `priceVariance` by sign, Cr AP `total`; its tax is Dr
   TAX_PAYABLE when the tenant setting `finance.inputTaxReclaimable` is true (default false) and otherwise a purchase
   cost on PPV, so tax payable is never understated.
7. **Cash.** Expenses paid from the drawer and supplier payments in cash record a PAID_OUT and cash drops a DROP on
   the open drawer through `payments.api` in the same transaction, then post Dr expense/AP/BANK / Cr CASH_DRAWER.
   `DrawerReconciled` posts the variance to CASH_OVER_SHORT. Floats, and paid-outs, paid-ins and drops recorded
   directly in payments (or offline at the till) are cash moving inside the shop and post nothing; money spent must be
   recorded as an expense (open question).
8. **Periods.** A period is `YYYY-MM` of the business date (tenant zone and cut-off). Rows of `fiscal_period` are
   created when first needed; a missing row is OPEN. Every posting creates the row if needed and takes it `FOR SHARE`;
   a close takes it `FOR UPDATE`, so no posting lands in a period while it closes. A posting dated in a CLOSING or
   CLOSED period is entered on the first day of the first open period after it, memo `late posting for <date>`.
9. **Close (FIN-06)** is a background operation (`platform.operation`) started by an internal event, so it survives a
   restart and is retried like any listener. It refuses synchronously a period not yet ended, already closed or closing,
   or with an earlier period that has journals and is open; it then checks no parked failed event of the tenant, POS
   clearing at zero per bill of the period (or acknowledged in the request) and every drawer session opened in the
   period reconciled (`payments.api`). A failed check fails the operation with its code; the period stays OPEN.
   Closing the month before `finance.fiscalYearStartMonth` (default January) is a year-end close: in the same
   transaction a YEAR_END_CLOSE journal moves the fiscal year's income and expense balances to RETAINED_EARNINGS,
   then the period is CLOSED and `PeriodClosed` is published. Profit and loss leaves YEAR_END_CLOSE journals out.
10. **Open items (FIN-07)** carry signed amounts per party: positive is owed to us by a customer or by us to a
    supplier, negative a credit not yet applied. A credit (collection, CREDIT refund of a return, the credit part of a
    void, supplier payment, supplier return) settles debits: the documents it names first (allocations on the
    collection, the bill of a return or void), then oldest due date first; what is left stays open as a credit and is
    applied to the next debit. Every settlement is a row of `open_item_settlement` (insert only); `party_balance` is
    the projection Σ open per party, kept in the same transaction and rebuildable. Due dates: bill date plus the
    customer's payment terms (party replica, retried until replicated); supplier invoices carry theirs; payable
    expenses take the request's or the supplier's terms. AR and AP account balances equal Σ open items by construction.
11. **Explicit allocations** of a collection travel on `PaymentCaptured.allocations` (additive field; payments takes
    them in `CapturePaymentRequest`), so the cashier's choice reaches finance without a second call.
12. **Credit exposure (FIN-10)** is Σ open receivables of the customer, never below zero. It follows the events, so a
    credit sale counts once finance has posted it (milliseconds to seconds later); two credit sales at the same moment
    may both pass the limit, as before (phase 03 minor finding).
13. **Imbalance (FIN-03).** `JournalEntry` cannot be built unbalanced: the builder throws `TechnicalException`
    (`LEDGER_IMBALANCE`); the posting service counts `bmp.ledger.imbalance`, and the listener interceptor now parks
    technical exceptions like domain ones (retrying a broken invariant cannot help), logs ERROR once and counts
    `bmp.events.failed`. A manual journal that does not balance is the client's mistake: 422 `JOURNAL_UNBALANCED`.
14. **Accounts.** The chart of `posting-rules.md` §1 is seeded on `TenantCreated` (operating expenses as 6000 rent,
    6100 utilities, 6200 wages, 6300 transport, 6400 packing materials, 6500 repairs, 6900 other, each with an expense
    category). System accounts may be renamed and renumbered but keep their type and stay active. An inactive account
    takes no manual lines; automatic postings still land on it, because the money moved.
15. **Overdue (FIN-08)** is a daily ShedLock job: the platform-admin connection lists tenants with items past due plus
    `finance.overdueGraceDays` (default 7); each tenant is then handled in its own context, and a customer is reported
    at most once per seven days (`overdue_notice`).
16. **Numbers** `JE-000001` per tenant from `finance.number_sequence` (ADR-012).

## Consequences
- The Inventory account, AR and AP reconcile to inventory's valuation and to the open items without manual entries;
  `MonthEndFlowIT` proves it.
- Finance consumes 15 event types; ordering is handled by retries (`OutOfOrderEventException`) where a journal needs
  another one first (voids, supplier returns, credit bills before their customer replica).
- Cash moved by hand in payments is not in the ledger; whether payments should publish its manual movements is an open
  question.
