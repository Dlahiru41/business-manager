# Finance module

The double-entry ledger fed by every other module's events, expenses, receivables and payables with their open items,
supplier payments, cash drops and fiscal periods (phase 04, FIN-01..FIN-11). Contract:
[`contracts/openapi/finance.yaml`](../../contracts/openapi/finance.yaml).

## Model

| Part | Kept as | Rules |
| --- | --- | --- |
| `Account` | `account` (JPA) | the chart of posting-rules.md §1 is seeded per tenant; `system_key` accounts keep their type and stay active (FIN-01) |
| `JournalEntry` | `journal_entry` + `journal_line` ledgers (INSERT/SELECT only) | balanced by construction (`LEDGER_IMBALANCE`); one per source event (`source_event_id` unique); a reversal points back with `reverses_id`, at most once (FIN-02..FIN-04) |
| `FiscalPeriod` | `fiscal_period` | `YYYY-MM` of the business date; OPEN → CLOSING → CLOSED; a missing row is OPEN (FIN-05, FIN-06) |
| `OpenItem` | `open_item` + `open_item_settlement` ledger | signed per party: positive owed, negative a credit not yet applied; settled by allocation, then oldest due date (FIN-07) |
| `party_balance` | projection | Σ open per party, kept in the posting transaction, rebuildable |
| `ExpenseCategory`, `Expense` | `expense_category` (JPA), `expense` ledger | a category posts to an EXPENSE account; expenses never change (FIN-09) |
| `SupplierPayment`, `CashDrop` | ledgers | settle payables (FIN-11); cash to the bank |

Amounts are kept at four decimals as the events carry them, so the Inventory account equals inventory's stock value to
the cent. Journal dimensions (variant, product, channel, location, bill) are columns because the close and the reports
query them.

## Postings (posting-rules.md §2)

Inbox-guarded listeners post `SaleCompleted`, `SaleReturned`, `SaleVoided` (the exact reversal of the sale, waiting for
it), `PaymentCaptured`, `PaymentRefunded`, `DrawerReconciled`, `StockIssued` (dated like its sale, return or void),
`StockReceived`, `StockAdjusted`, `StockCountPosted`, `StockTransformed`, `GoodsReceived` and `SupplierInvoicePosted`.
Each journal goes to its period, or to the first open day after a closed one with the memo `late posting for <date>`
(FIN-05). Choices the posting rules leave open are in [ADR-032](../../docs/decisions/ADR-032-finance-modelling-choices.md):
device totals differences, returns to suppliers at current cost, true-ups, opening stock, input tax, wastage reasons.

Open items: credit bills (BILL), account collections (COLLECTION, with the cashier's allocations), CREDIT refunds of
returns (RETURN), the credit part of voids (VOID), refunded collections (REFUND), supplier invoices (SUPPLIER_INVOICE),
payable expenses (EXPENSE) and supplier payments (SUPPLIER_PAYMENT). Due dates come from the party's payment terms
(replicated from party events).

## Events

- In: the events above, `TenantCreated` (seeds the chart and its expense categories), `PartyCreated`/`PartyUpdated`
  (payment terms), and the module-internal `PeriodCloseRequested`.
- Out: `JournalPosted`, `ExpenseRecorded`, `ReceivableOverdue` (FIN-08, daily job, at most weekly per customer),
  `PeriodClosed`.

## API for other modules

- `finance.api.LedgerQueries`: profit and loss, aging, overdue receivables, POS clearing differences and freshness for
  reporting (REP-02..REP-07).
- `sales.api.spi.CreditExposureProvider` (FIN-10): Σ open receivables of the customer, never below zero; replaces
  sales' fallback, so a collection raises the credit a customer may still take.

## Endpoints

| Area | Operations | Permission |
| --- | --- | --- |
| Accounts | list, add, get, rename/renumber/reclassify/deactivate (If-Match) | `finance:read`, `finance:write` |
| Journals | list (period, source, account, dates), get, manual (memo, balanced), reverse | `finance:read`, `finance:journal` |
| Ledger | trial balance (`asOf` or `period`), profit and loss (`from`..`to`) | `finance:read` |
| Expenses | categories (list, add, change), expenses (list, record, get) | `finance:read`, `finance:write`, `finance:expense` |
| Receivables / Payables | aging, statements, supplier payments | `finance:read`, `finance:pay-supplier` |
| Cash drops | list, record (DROP on the drawer, Dr BANK / Cr CASH_DRAWER) | `finance:read`, `finance:cash-drop` |
| Periods | list, get, close checks, close (202, `GET /api/v1/operations/{id}`) | `finance:read`, `finance:close-period` |
| Administration | rebuild `party_balance` | `finance:admin` |

Period close (FIN-06): refused at once when the period has not ended, is closed or closing, or an earlier period with
journals is open; then, in the background: no parked failed events, POS clearing at zero per bill (or acknowledged),
every drawer session opened in the period reconciled. The last period of the fiscal year (`finance.fiscalYearStartMonth`)
first moves the year's income and expenses to retained earnings (YEAR_END_CLOSE, left out of profit and loss).

## Settings

`finance.fiscalYearStartMonth` (1), `finance.overdueGraceDays` (7), `finance.inputTaxReclaimable` (false),
`finance.periodLockDays` (5, not used yet).

## Personal data

None: parties are ids; names, phones and addresses stay in the party module. Free-text memos and expense descriptions
are entered by staff and are not logged.
