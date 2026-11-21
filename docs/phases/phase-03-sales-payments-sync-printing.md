# Phase 03 — Sales, payments, offline sync and printing

## Goal
Selling: bills (retail and wholesale), discounts, tenders, credit sales, voids and returns; payments and cash drawer reconciliation; idempotent offline sync for tills; receipt rendering and a Java print agent driving the shop's existing receipt printer. Completing a bill issues stock and records cost and margin automatically.

## Read before starting
- `CLAUDE.md`, `docs/PROGRESS.md`
- `docs/standards/01`–`09` (re-read `03` §5 idempotency, `04` §7–8, `07` §4–6)
- `docs/reference/domain-model.md`, `data-model.md` (sales, payments, documents, sync), `event-catalogue.md` §2–3

## Scope
`modules/sales`, `modules/payments`, `modules/sync`, `modules/documents`, new `libs/receipt-renderer`, new `print-agent/` application, inventory listeners for sales events.

Out of scope: the POS UI. Provide `libs/test-support` `PosSimulator` (builds offline bills/payloads like a till would) and `http/pos-simulator.http`.

---

## Sales rules

| Id | Rule |
| --- | --- |
| SAL-01 | Bill ids are UUIDv7 generated on the device; numbers are `<registerPrefix>-<6-digit seq>` allocated on the device per register. Same id resubmitted → idempotent replay; same number with a different id → 409 `BILL_NUMBER_CONFLICT`. |
| SAL-02 | Two entry paths ending in the same `Bill.complete(...)`: **online** (draft → edit lines → complete) and **direct submission** of an already-completed bill (POS online or via sync). |
| SAL-03 | Line prices: online path re-resolves via `pricing.api` and rejects mismatches (422 `PRICE_MISMATCH`) unless the line carries an approved override (`sales:price-override`). Offline submissions are **accepted** with the device's prices, validated against the stated `priceListVersion`; mismatches are flagged `PRICE_MISMATCH` and logged at WARN. |
| SAL-04 | Totals: line = round(qty × unitPrice) − lineDiscount; bill discount allocated across lines by largest remainder on line net; tax per PRC-07; grand total = Σ lines. Server recomputes. Online: any difference → 422 `TOTALS_MISMATCH`. Offline: device totals are authoritative (the customer paid them); a difference > 0.01 is stored, flagged `TOTALS_MISMATCH` and alerted. |
| SAL-05 | Discounts above `maxCashierDiscountPct` need `approvedBy` with `sales:discount-unlimited`; online → 403 `DISCOUNT_LIMIT_EXCEEDED` without it; offline → accept and flag. |
| SAL-06 | Tenders: Σ tenders (including CREDIT) = grand total exactly; cash `tendered ≥ amount`, `change = tendered − amount`; CARD/TRANSFER require a reference (tenant setting can relax). |
| SAL-07 | CREDIT tender requires a customer with `creditAllowed`; online checks `exposure + amount ≤ creditLimit` (409 `CREDIT_LIMIT_EXCEEDED`, override with `sales:credit-override`); offline accepts and flags `CREDIT_LIMIT_EXCEEDED`. Exposure comes from the SPI `sales.api.spi.CreditExposureProvider`. This phase ships the default `@ConditionalOnMissingBean` implementation inside sales: Σ CREDIT tenders on the customer's bills (conservative, because collections are ignored). Phase 04 finance provides the real implementation from receivables. Sales must **not** call payments or finance directly (dependency cycle). |
| SAL-08 | Completed bills are immutable. `SaleCompleted` carries lines with `qtyBase` and `stockState` (from the catalog replica) so inventory needs no lookup. |
| SAL-09 | Void: only COMPLETED with no returns; reason required; `sales:void`; if `voidRequiresManager` then `approvedBy`; only within the same business date (else use a return, 409 `VOID_WINDOW_PASSED`). → `SaleVoided`. |
| SAL-10 | Return: within `returnWindowDays` (override `sales:return-override`); per line qty ≤ sold − already returned (409 `RETURN_QTY_EXCEEDED`); amount = pro-rata of the line's final total incl. allocated discount and tax; `restock` flag per line; refund tenders given or default to original methods (cash if mixed); credit note `CN-000001`. → `SaleReturned`. |
| SAL-11 | Sales does **not** store cost or margin (consuming `StockIssued` would create a sales ↔ inventory cycle). Cost per bill line is on `StockIssued` (`sourceLineId`); per-bill margin is served by reporting in phase 04. In this phase, tests assert costs on the inventory movements. |
| SAL-12 | Registers and shifts: a bill references its register and shift; online bills need an OPEN shift (409 `SHIFT_NOT_OPEN`); offline bills attach to the shift by timestamp or to a synthetic "offline" shift. |
| SAL-13 | `business_date` from `completedAt` + tenant time zone + cut-off hour. |
| SAL-14 | Channel RETAIL/WHOLESALE selects the default price list; wholesale requires a customer only if the tenant setting `wholesaleRequiresCustomer=true`. |
| SAL-15 | Weighed lines: quantity in g/kg with up to 3 decimals in kg; `weighed=true`. |

## Payments rules

| Id | Rule |
| --- | --- |
| PAY-01 | On `SaleCompleted`: one `payment` per non-CREDIT tender (idempotent on tender id) → `PaymentCaptured`. Cash tenders add a `cash_movement` SALE to the register's open drawer session. |
| PAY-02 | Account collections: `POST /payments` with `customerId` and no bill → `PaymentCaptured` (kind ACCOUNT). |
| PAY-03 | Refunds ≤ captured − refunded (409 `REFUND_EXCEEDS_PAYMENT`); cash refunds need an open drawer session on that register. |
| PAY-04 | Drawer session: at most one OPEN per register; opening float; movements FLOAT, SALE, REFUND, PAID_OUT, PAID_IN, DROP; `expected = float + sales − refunds − paidOut + paidIn − drops`; count by denomination (LKR denominations configurable); variance = counted − expected; reconcile → `DrawerReconciled`; |variance| > `drawerVarianceAlertAmount` → WARN + notification. |
| PAY-05 | Reject any field that looks like a card number (13–19 digits passing Luhn) with 422 `CARD_DATA_NOT_ALLOWED`. |
| PAY-06 | `GET /payments/summary?date=&registerId=` returns totals by method for the business date (card total to compare with the bank). |

## Sync rules

| Id | Rule |
| --- | --- |
| SYN-01 | Only `DEVICE` principals; device must be ACTIVE and bound to a register; the register's location applies to all records. |
| SYN-02 | `GET /sync/bootstrap`: catalog snapshot, price list snapshots, tax rates, sales/pricing settings subset, receipt template, register info (prefix, last accepted number), customers with credit policy (id, display name, phone last 4), stock balances for the location; each dataset with a version/cursor. |
| SYN-03 | `GET /sync/changes?cursors=...` returns deltas per dataset since each cursor. |
| SYN-04 | `POST /sync/uploads`: ordered batch ≤ 1,000 records of types `SHIFT_OPEN`, `DRAWER_OPEN`, `BILL`, `VOID`, `RETURN`, `CASH_MOVEMENT`, `DRAWER_COUNT`, `SHIFT_CLOSE`, `WASTAGE`. Each record is processed in its **own transaction** via the owning module's `api` command; result per record `ACCEPTED` / `DUPLICATE` / `REJECTED` + Problem. A record depending on a rejected one is `REJECTED` with `DEPENDENCY_REJECTED`. |
| SYN-05 | Every record id is its idempotency key (`sync.upload_record`). Replays return the original status. |
| SYN-06 | Bill number gaps per register detected after each upload → `BillNumberGapDetected` (once per gap). |
| SYN-07 | Device clock skew recorded from the `deviceTime` header; > 5 min → flag on the device and WARN. |
| SYN-08 | Records older than `offlineMaxHours` are accepted and flagged `LATE_SYNC`. |

## Documents and printing rules

| Id | Rule |
| --- | --- |
| DOC-01 | `libs/receipt-renderer` (pure Java, no Spring) renders a `ReceiptModel` + `ReceiptTemplate` to (a) ESC/POS bytes for 58 mm (32 chars) and 80 mm (48 chars), (b) PDF (OpenPDF or PDFBox), (c) plain text preview. |
| DOC-02 | Receipt content: shop header, address, phone, tax number (if set), bill number, date/time (tenant tz), cashier, register, lines (name, qty + unit, unit price, line total), discounts, tax, total, tenders, change, customer (credit bills), footer, "COPY" banner on reprints, optional QR with bill id. |
| DOC-03 | Sinhala and Tamil text: thermal printers usually lack these fonts, so non-Latin text is rasterised with Java2D (Noto Sans Sinhala / Noto Sans Tamil bundled) and sent as ESC/POS raster (`GS v 0`). Latin-only receipts use text mode. |
| DOC-04 | Cash drawer kick (`ESC p`) after cash tenders, configurable. |
| DOC-05 | Reprints and back-office prints: `POST /bills/{id}/reprint` → `print_job` for the location's agent; the agent fetches jobs by long-polling; status PRINTED/FAILED reported back. |
| DOC-06 | Receipt templates are versioned (tenant module); the agent caches the latest template so it can print offline. |

## Print agent (`print-agent/`)

- Java 25 Spring Boot app (web server bound to `127.0.0.1` only), packaged as a runnable JAR plus jpackage installers for Windows (primary) and Linux.
- Local API for the POS: `POST http://127.0.0.1:9123/v1/print` with a `ReceiptModel` JSON (renders locally with `receipt-renderer` and the cached template), `POST /v1/drawer/open`, `GET /v1/status`. Protected by a local shared token configured at install.
- Printer connections: OS print queue via `javax.print` (USB), raw TCP 9100 (network), serial/USB-serial via jSerialComm. Configured in `agent.yaml`.
- Cloud link (when online): authenticates with the device credential, refreshes the template, long-polls `/api/v1/print-agents/{id}/jobs:next`, reports job results and printer status (paper out, offline).
- Resilience: local job queue persisted to an embedded H2/SQLite file; retries with back-off; never loses a print request.
- Logging: JSON logs to rolling files, no receipt contents at INFO.

---

## Tasks

### T03-01 Contracts
`sales.yaml`, `payments.yaml`, `sync.yaml`, `documents.yaml` and AsyncAPI events.

| Module | Method | Path | Permission |
| --- | --- | --- | --- |
| sales | GET/POST/PATCH | `/api/v1/registers[/{id}]` | `sales:admin` |
| sales | POST | `/api/v1/shifts`, `/api/v1/shifts/{id}/close` | `sales:create` |
| sales | POST | `/api/v1/bills` (draft or completed submission, `@Idempotent`) | `sales:create` |
| sales | PATCH | `/api/v1/bills/{id}` (draft only, If-Match) | `sales:create` |
| sales | POST | `/api/v1/bills/{id}/complete` | `sales:create` |
| sales | POST | `/api/v1/bills/{id}/void` | `sales:void` |
| sales | POST | `/api/v1/bills/{id}/returns` | `sales:return` |
| sales | GET | `/api/v1/bills`, `/api/v1/bills/{id}` | `sales:read` |
| sales | GET | `/api/v1/credit-notes[/{id}]` | `sales:read` |
| payments | POST | `/api/v1/payments` (account collection) | `payments:capture` |
| payments | POST | `/api/v1/payments/{id}/refunds` | `payments:refund` |
| payments | GET | `/api/v1/payments`, `/api/v1/payments/summary` | `payments:read` |
| payments | POST | `/api/v1/drawer-sessions`, `/{id}/movements`, `/{id}/count`, `/{id}/reconcile` | `drawer:operate-own` / `payments:reconcile` |
| payments | GET | `/api/v1/drawer-sessions[/{id}]`, `/{id}/expected` | `payments:read` |
| sync | GET | `/api/v1/sync/bootstrap`, `/api/v1/sync/changes` | `sync:read` |
| sync | POST | `/api/v1/sync/uploads` | `sync:upload` |
| sync | GET | `/api/v1/sync/status` | `sync:read` |
| documents | POST | `/api/v1/documents/receipts:render` (format=ESCPOS|PDF|TEXT) | `documents:render` |
| documents | POST | `/api/v1/bills/{id}/reprint` | `sales:read` |
| documents | GET/POST | `/api/v1/print-agents[/{id}]`, `GET /{id}/jobs:next`, `POST /api/v1/print-jobs/{id}/result` | `documents:print` |

### T03-02 Sales domain (TDD)
`Bill` aggregate (draft editing, complete, void, return bookkeeping), `BillTotalsCalculator`, `DiscountAllocator`, `TenderValidator` (sealed `Tender` hierarchy), `CreditNote`. Unit tests for SAL-04..07, 09, 10, 13, 15 including rounding edge cases and mixed tenders.

### T03-03 Sales persistence, API, events, registers and shifts
Replicas needed: variant/packaging units from catalog events (for `qtyBase`, `stockState`, names), customers from party events. Implement SAL-01..03, 08, 12, 14.

### T03-04 Inventory listeners for sales
- `SaleCompleted` → SALE movements per line (INV-05..07), `StockIssued` with `costAmount` per `sourceLineId`.
- `SaleReturned` → SALE_RETURN movements at the **original** issue unit cost for `restock=true` lines (look up original movements by `source_line_id`); non-restock lines create **no** movement, because the goods already left stock on the original sale. Their cost stays in COGS, and reporting shows them as "returned, not restocked" so the loss is visible.
- `SaleVoided` → reverse the bill's SALE movements at original cost.
- Idempotency + tenant context tests; void-after-return impossible (sales rule) but guard anyway.

### T03-05 SPIs: credit exposure (SAL-07) and business activity (TEN-02)
`sales.api.spi.CreditExposureProvider` with the default implementation in sales; tests for limit checks online and flagging offline. Sales also implements `tenant.api.spi.BusinessActivityProbe` (true once any bill exists), which locks currency and time zone.

### T03-06 Payments module
PAY-01..06, drawer sessions, LKR denominations config, `PaymentCommands` / `PaymentQueries` in `payments.api` (used by sync and, in phase 04, by finance for drawer paid-outs).

### T03-07 Void and return process manager
Sales cannot listen to payments or inventory events (cycle), so sales does not drive the return. The flow is choreographed: sales publishes `SaleReturned` with `refundTenders` and `restock` flags. Payments refunds and publishes `PaymentRefunded`; inventory restocks. Each listener is idempotent and retried by the event registry. Return completeness is monitored by reporting/notification in phase 04, which flags returns without a matching refund after 1 hour. Tests cover partial failure (payments listener fails then succeeds on retry) and duplicate delivery. Phase 06 can move this to a Temporal workflow in a separate orchestration service that may depend on all three modules.

### T03-08 Sync module
SYN-01..08. Upload processing calls `sales.api.SalesCommands`, `payments.api.PaymentCommands`, `inventory.api.InventoryCommands` (add these command facades to those modules' `api` packages; they delegate to handlers and run in their own transaction). Gap detection, clock skew, late-sync flags. Load test: 1,000-record batch < 10 s locally.

### T03-09 Receipt renderer and documents module
`libs/receipt-renderer` (DOC-01..04) with golden-file tests (ESC/POS byte output for 58/80 mm, PDF text extraction, Sinhala raster snapshot). Documents module: render endpoint, print agents, print jobs (DOC-05, 06).

### T03-10 Print agent application
As specified above; integration test with a fake TCP printer (socket server capturing bytes) and an offline-queue test (cloud unreachable → print still succeeds locally; job results synced later).

### T03-11 Full-day flow test
`ShopDayFlowIT` with `PilotShopFixture` + `PosSimulator`:
1. Open shift and drawer (float 5,000.00).
2. Online retail bill: 10 large red eggs + one 750 ml oil bottle + 350 g loose Karola (weighed), cash with change.
3. Wholesale bill: 300 large red eggs (quantity-break price) + 2 oil cans, split card + cash.
4. Go "offline": the simulator builds 20 bills locally, including one CREDIT sale to a credit customer and one with a cashier discount above the limit; upload as one sync batch → all ACCEPTED, flags set where expected; re-upload → all DUPLICATE.
5. Skip one bill number → `BillNumberGapDetected`.
6. Return 2 eggs from bill 2 with restock, refund cash.
7. Void a same-day bill with manager approval.
8. Count the drawer with a 100.00 shortage → variance and `DrawerReconciled`.
9. Assert: stock balances (SEALED/OPEN oil, eggs, Karola), every bill line has a SALE movement with cost, payments summary by method, inventory movements count, and that every event was processed once.

---

## Definition of Done
- [ ] Every SAL/PAY/SYN/DOC rule has a named test; `ShopDayFlowIT` passes.
- [ ] Sync upload is idempotent under retries and partial failures (test kills processing mid-batch and resubmits).
- [ ] Print agent prints to the fake printer offline and online; installer builds on CI for Windows.
- [ ] Logging check: completed bill produces exactly one INFO `Bill completed` line without customer PII.
- [ ] PIT ≥ 70% on sales, payments, sync domain packages.
- [ ] Contracts, READMEs, `.http` files, PROGRESS summary updated.
