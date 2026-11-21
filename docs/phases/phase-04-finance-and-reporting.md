# Phase 04 — Finance and reporting

## Goal
Every money effect becomes a balanced double-entry journal automatically; expenses, receivables (customer credit) and payables (suppliers) are tracked; profit is reported per bill, per product and overall; the owner gets a dashboard. Month-end profit and stock value reconcile to the ledger without manual adjustment.

## Read before starting
- `CLAUDE.md`, `docs/PROGRESS.md`
- `docs/standards/01`, `02` §5, `04`, `06` §6, `07`, `09`
- `docs/reference/posting-rules.md` (all — binding), `data-model.md` (finance, reporting), `event-catalogue.md`

## Scope
`modules/finance`, `modules/reporting`, `modules/notification` (basic), sales SAL-07 switch to finance exposure.

---

## Finance rules

| Id | Rule |
| --- | --- |
| FIN-01 | Chart of accounts seeded on `TenantCreated` from `posting-rules.md` §1; `system_key` accounts cannot be deleted or change type. |
| FIN-02 | One journal per source event, idempotent on `source_event_id` (unique). Posting rules exactly as `posting-rules.md` §2, implemented as one `PostingRule` strategy per event type in `finance.domain.posting`. |
| FIN-03 | Σ debit = Σ credit per journal, asserted before insert; violation → `TechnicalException(LEDGER_IMBALANCE)` + metric + ERROR log; the event goes to `failed_event`. |
| FIN-04 | Journals are immutable; corrections are reversing journals (`reverses_id`). Manual journals need `finance:journal` and a memo. |
| FIN-05 | Period = `YYYY-MM` of the business date. Postings into CLOSED periods go to the first open period with memo `late posting for <original date>`. |
| FIN-06 | Period close (`finance:close-period`): preconditions — no failed events for the tenant, POS clearing nets to zero for the period or the differences are listed and acknowledged, all drawer sessions of the period reconciled; then status CLOSED. Year-end close moves P&L to retained earnings. Implemented as an async operation (202). |
| FIN-07 | Open items: every credit bill creates an AR open item; collections settle oldest first unless allocated explicitly; supplier invoices create AP open items; supplier payments settle them. Aging buckets 0–30, 31–60, 61–90, 90+ days from due date. |
| FIN-08 | `ReceivableOverdue` published daily (ShedLock job) for customers with items past due + 7 days; once per customer per week. |
| FIN-09 | Expenses: category → account; paid from CASH_DRAWER (requires an open drawer session; creates a PAID_OUT cash movement via `payments.api`), BANK, or PAYABLE (creates AP open item). Attachments via object storage keys. |
| FIN-10 | Finance implements `sales.api.spi.CreditExposureProvider` (open AR items for the customer). This automatically replaces the default implementation from phase 03 (SAL-07). Sales code does not change. |
| FIN-11 | Supplier payments: `POST /supplier-payments` (BANK or CASH_DRAWER) settling AP open items. |

## Reporting rules

| Id | Rule |
| --- | --- |
| REP-01 | Reporting owns only read models built from events (`fact_*`, `daily_summary`), rebuildable by replaying events from the publication registry / module rebuild endpoints. |
| REP-02 | Profit levels per `posting-rules.md` §4: per bill and per product/variant/channel/location/day from `fact_sale_line`, which joins `SaleCompleted` lines with `StockIssued` costs by `sourceLineId` (either event may arrive first, so upsert both halves). Overall P&L comes from finance journals via `finance.api.LedgerQueries`; reporting never recomputes accounting. Endpoint `GET /reports/bills/{id}/profit`. |
| REP-07 | Data-quality monitors: returns without a matching refund after 1 h, bills without `StockIssued` after 10 min, POS clearing non-zero after 1 business day. Each is listed at `GET /reports/data-quality` and triggers an owner notification. |
| REP-03 | Every report response includes `dataFreshAsOf` (last event processed time) and the filters applied. |
| REP-04 | Reports: profit (groupBy bill|product|variant|channel|location|day|month), sales (by product, channel, payment method, cashier, hour), stock valuation (delegates to inventory), wastage (by reason, product, cost), cash position (expected vs counted per drawer), receivables aging, payables aging, owner dashboard. |
| REP-05 | Exports: CSV and XLSX (Apache POI streaming `SXSSFWorkbook`) and PDF, async via operations, stored in object storage with a 7-day signed link. CSV cells starting with `= + - @` are escaped. |
| REP-06 | Owner dashboard (`GET /dashboards/owner?date=`): today's sales, gross margin, bills count, cash expected vs counted, top 5 products by margin, low-stock count, overdue receivables total, negative-stock items, unsynced devices. One call, < 300 ms p95 using `daily_summary`. |

## Notification (basic)

- `outbound_message` with channels EMAIL (SMTP; Mailpit locally) and SMS (gateway port with a logging fake adapter; real provider chosen later via ADR).
- Consumes `StockLevelLow`, `ReceivableOverdue`, `DrawerReconciled` (variance over threshold), `BillNumberGapDetected` → owner notifications per tenant notification settings (opt-in per type).
- Templates in `en`/`si`/`ta`, rendered with a simple template engine (Thymeleaf text mode or Mustache — pick one, ADR).

---

## Tasks

### T04-01 Contracts
`finance.yaml`: accounts, journals (read; manual create), expense categories, expenses, receivables (`/receivables`, `/receivables/{customerId}/statement`), payables, supplier payments, periods (`/periods`, `/periods/{period}/close` → 202), cash drops. `reporting.yaml`: endpoints for REP-04..06, `/reports/exports`, `/operations/{id}`. Events: `ExpenseRecorded`, `JournalPosted`, `ReceivableOverdue`, `PeriodClosed`.

### T04-02 Finance domain (TDD)
`Account`, `JournalEntry` (balanced by construction: builder refuses to build unbalanced), `PostingRule` per event with table-driven tests using the exact examples from `posting-rules.md`, `OpenItem` settlement (oldest first), `FiscalPeriod`.

### T04-03 Finance listeners and persistence
Listeners for every event in `posting-rules.md` §2 (inbox-guarded, tenant context). Journal writer with `JdbcClient` batch insert; `party_balance` projection; rebuild endpoint and equality test.

### T04-04 Expenses, receivables, payables, supplier payments, periods
FIN-06..11 with APIs; implement the `CreditExposureProvider` SPI (FIN-10) and add a test proving sales now uses it (a collection raises available credit).

### T04-05 Reporting read models
Listeners for `SaleCompleted`, `StockIssued`, `SaleReturned`, `PaymentRefunded`, `SaleVoided`, `StockAdjusted`, `StockTransformed`, `PaymentCaptured`, `ExpenseRecorded`, `DrawerReconciled`; `daily_summary` maintenance; rebuild command.

### T04-06 Reports, dashboard and exports
REP-02..07 with `JdbcClient` SQL (window functions where useful), indexes, query-count assertions, export jobs with ShedLock-managed cleanup.

### T04-07 Notification module (basic)

### T04-08 Month-end reconciliation flow test
`MonthEndFlowIT` building on `ShopDayFlowIT` data over several business days:
1. Record expenses: rent (BANK), 200 empty bottles (CASH_DRAWER), electricity (PAYABLE).
2. Supplier invoice for the egg receipt with a small price difference → PPV posted.
3. Collect part of the credit sale → aging updated.
4. Close the month.
Assert:
- Trial balance balances; Inventory account balance = stock valuation report total (to the cent).
- P&L profit = revenue − returns − discounts − COGS − wastage − variances − expenses, and equals the reporting overall profit.
- Profit by product: bottled oil vs can oil margins computed correctly.
- AR balance = Σ open customer items; AP balance = Σ open supplier items.
- POS clearing = 0.

---

## Definition of Done
- [ ] Every FIN/REP rule has a named test; `MonthEndFlowIT` passes to the cent.
- [ ] No journal can be unbalanced (property test generating random sales/returns/adjustments).
- [ ] Dashboard p95 < 300 ms on a dataset of 100k bills (perf IT, tagged `@Tag("perf")`, run in CI nightly).
- [ ] PIT ≥ 70% on finance and reporting domain packages.
- [ ] Contracts, READMEs, `.http` files, PROGRESS summary updated.
