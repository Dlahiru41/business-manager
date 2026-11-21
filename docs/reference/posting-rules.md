# Accounting posting rules (finance module)

Every event below produces exactly one balanced journal entry (Σ debit = Σ credit), idempotent on `source_event_id`. Account codes are from the default chart; tenants may rename accounts but `system_key` stays fixed.

## 1. Default chart of accounts (seeded on `TenantCreated`)

| Code | Name | Type | system_key |
| --- | --- | --- | --- |
| 1000 | Cash in drawer | ASSET | CASH_DRAWER |
| 1010 | Bank | ASSET | BANK |
| 1020 | Card clearing | ASSET | CARD_CLEARING |
| 1030 | Mobile / transfer clearing | ASSET | TRANSFER_CLEARING |
| 1040 | Cheques in hand | ASSET | CHEQUES |
| 1100 | Accounts receivable | ASSET | AR |
| 1110 | POS clearing (walk-in) | ASSET | POS_CLEARING |
| 1200 | Inventory | ASSET | INVENTORY |
| 2000 | Accounts payable | LIABILITY | AP |
| 2050 | Goods received not invoiced | LIABILITY | GRNI |
| 2100 | Tax payable | LIABILITY | TAX_PAYABLE |
| 2200 | Customer credits / advances | LIABILITY | CUSTOMER_CREDIT |
| 3000 | Owner's equity | EQUITY | EQUITY |
| 3100 | Retained earnings | EQUITY | RETAINED_EARNINGS |
| 4000 | Sales revenue | INCOME | SALES |
| 4100 | Sales returns | INCOME (contra) | SALES_RETURNS |
| 4200 | Sales discounts | INCOME (contra) | SALES_DISCOUNTS |
| 5000 | Cost of goods sold | EXPENSE | COGS |
| 5100 | Wastage and spoilage | EXPENSE | WASTAGE |
| 5200 | Stock count variance | EXPENSE | COUNT_VARIANCE |
| 5210 | Purchase price variance | EXPENSE | PPV |
| 5300 | Cash over / short | EXPENSE | CASH_OVER_SHORT |
| 6000–6900 | Operating expenses (rent, utilities, wages, transport, packing materials, repairs, other) | EXPENSE | EXP_* |

## 2. Rules

Notation: `Dr X / Cr Y amount`. `net` = revenue excluding tax; `tax` = tax amount; `gross` = grand total.

| Event | Journal lines | Party / dimensions |
| --- | --- | --- |
| `SaleCompleted` | Dr AR (customer bill) **or** POS_CLEARING (walk-in) `gross` · Cr SALES `net before discount` · Dr SALES_DISCOUNTS `discount` · Cr TAX_PAYABLE `tax` | party = customer; dims per line: variant, channel, location, billId |
| `PaymentCaptured` (bill) | Dr CASH_DRAWER / CARD_CLEARING / TRANSFER_CLEARING / CHEQUES `amount` · Cr AR or POS_CLEARING `amount` | party = customer if any |
| `PaymentCaptured` (account collection) | Dr cash-type account · Cr AR | party = customer |
| `StockIssued` (sale) | Dr COGS · Cr INVENTORY `Σ costAmount` | dims per line |
| `SaleReturned` | Dr SALES_RETURNS `net` · Dr TAX_PAYABLE `tax` · Cr AR / POS_CLEARING `gross`; restocked lines: Dr INVENTORY · Cr COGS `original cost` | |
| `PaymentRefunded` | Dr AR / POS_CLEARING · Cr cash-type account | |
| `SaleVoided` | Exact reversal of the bill's `SaleCompleted`, payment and `StockIssued` entries (new entries with `reverses_id`) | |
| `GoodsReceived` | Dr INVENTORY · Cr GRNI `Σ qty × unitCost` | party = supplier |
| `SupplierInvoicePosted` | Dr GRNI `matched receipt value` · Dr/Cr PPV `difference` · Dr TAX_PAYABLE `input tax` (if reclaimable) · Cr AP `total` | party = supplier |
| Supplier payment (phase 04 `POST /supplier-payments`) | Dr AP · Cr BANK / CASH_DRAWER | party = supplier |
| `StockAdjusted` reason WASTAGE/SPOILAGE/BREAKAGE/SPILLAGE/EXPIRED | Dr WASTAGE · Cr INVENTORY `cost` | |
| `StockAdjusted` / `StockCountPosted` variance | loss: Dr COUNT_VARIANCE · Cr INVENTORY; gain: Dr INVENTORY · Cr COUNT_VARIANCE | |
| `StockTransformed` | No entry when Σ input cost = Σ output cost (cost absorbed). If packing materials are **non-stock** expenses, none here (they were expensed on purchase). | |
| `ExpenseRecorded` | Dr expense category account · Cr CASH_DRAWER (paid from drawer) / BANK / AP | party = supplier if any |
| `DrawerReconciled` variance ≠ 0 | short: Dr CASH_OVER_SHORT · Cr CASH_DRAWER; over: reverse | |
| Cash drop to bank | Dr BANK · Cr CASH_DRAWER | |
| `PeriodClosed` (year end only) | Close INCOME/EXPENSE to RETAINED_EARNINGS | |

## 3. Invariants and checks

- POS_CLEARING nets to zero per completed walk-in bill once all payments are captured; a daily job flags non-zero bills older than 1 business day (offline sync gaps or mismatched tenders).
- Card clearing should match the bank's card settlement; provide a report, not automatic matching (phase 04).
- `bmp.ledger.imbalance` metric increments if any journal fails Σ debit = Σ credit — this must never happen; the posting code asserts it before insert and throws `TechnicalException(LEDGER_IMBALANCE)`.
- Postings into a CLOSED period are rejected; late events are posted to the first open period with a memo referencing the original date.

## 4. Profit definitions (reporting)

| Level | Formula |
| --- | --- |
| Per bill | `grand_total − tax_total − Σ line cost` (gross margin), from `reporting.fact_sale_line` |
| Per product / variant / channel | Σ line revenue (net of discount and tax) − Σ line cost |
| Overall for a period | (SALES − SALES_RETURNS − SALES_DISCOUNTS) − COGS − WASTAGE − COUNT_VARIANCE − PPV − CASH_OVER_SHORT − operating expenses |
