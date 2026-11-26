# Sales module

Registers, shifts, bills and credit notes of a tenant (phase 03, rules SAL-01..SAL-15). Contract:
[`contracts/openapi/sales.yaml`](../../contracts/openapi/sales.yaml).

## Aggregates

| Aggregate | Parts | Invariants |
| --- | --- | --- |
| `Register` | code, bill prefix, location, bound device, last accepted number | code and prefix unique per tenant; the last number only moves forward |
| `Shift` | register, cashier, opened/closed, offline marker | one OPEN shift per register; closed is final; an offline shift is synthetic and closed |
| `Bill` | lines (qty, qtyBase, stock state, unit price, line/rule/allocated discount, tax, returned qty/amount/tax), tenders, totals, flags | DRAFT → COMPLETED → PARTIALLY_RETURNED / RETURNED, or VOIDED; only drafts change; tenders sum to the grand total; returns never exceed what was sold |
| `CreditNote` | lines (bill line, qty, amount, tax, restock), refund tenders | append-only: written once with its number, never changed |

## Rules

- **SAL-01** the till sends the bill id and its number `<prefix>-<6 digits>`. The same id again replays the stored bill;
  the same number with another id is 409 `BILL_NUMBER_CONFLICT`.
- **SAL-02** two paths end in the same `Bill.complete(...)`: draft → `PATCH` (If-Match) → `/complete`, or `POST /bills`
  with `complete: true`. The sync module submits offline bills through `SalesCommands` (T03-08).
- **SAL-03** online prices are resolved again through `pricing::api` (`PricingQueries`): no price → 422
  `PRICE_NOT_FOUND`, a different price → 422 `PRICE_MISMATCH` unless the line carries a `priceOverride` approved by a
  holder of `sales:price-override`. Offline bills keep the device's prices and are flagged `PRICE_MISMATCH`.
- **SAL-04** the server recomputes the totals (`BillTotalsCalculator`); online, any difference → 422
  `TOTALS_MISMATCH`; offline, the device's totals stand and a difference above 0.01 is flagged.
- **SAL-05** cashier discounts above `maxCashierDiscountPct` need an approver holding `sales:discount-unlimited`
  (403 `DISCOUNT_LIMIT_EXCEEDED`). Discounts from pricing rules (`ruleDiscount`) do not count towards the limit.
- **SAL-06** tenders add up to the grand total exactly (`TENDER_MISMATCH`); cash `tendered ≥ amount`; CARD and
  TRANSFER need a reference unless `tenderReferenceRequired=false`; references that look like a card number are refused
  (`CARD_DATA_NOT_ALLOWED`).
- **SAL-07** CREDIT needs `allowCreditSales` and a customer with `creditAllowed` (`CREDIT_NOT_ALLOWED`); online,
  exposure + amount above the limit is 409 `CREDIT_LIMIT_EXCEEDED` unless `creditApprovedBy` names a holder of
  `sales:credit-override`. Exposure comes from the `CreditExposureProvider` SPI.
- **SAL-08** completed bills are immutable; `SaleCompleted` carries `qtyBase` and `stockState` from the catalog replica.
- **SAL-09** void: COMPLETED without returns, same business date (`VOID_WINDOW_PASSED`), reason, `sales:void`, and an
  approver when `voidRequiresManager`.
- **SAL-10** return: within `returnWindowDays` (override `sales:return-override`), at most sold − returned per line
  (`RETURN_QTY_EXCEEDED`), pro-rata amount and tax, refunds default to the original methods; credit notes `CN-000001`.
- **SAL-11** sales stores no cost or margin; inventory's `StockIssued` carries them.
- **SAL-12** online bills need the register's OPEN shift (409 `SHIFT_NOT_OPEN`); offline bills attach to the shift they
  name, the shift running at their time, or a synthetic offline shift (flag `OFFLINE_SHIFT`).
- **SAL-13** `businessDate` from `completedAt`, the tenant time zone and cut-off hour.
- **SAL-14** the channel selects the price list; WHOLESALE needs a customer only if `wholesaleRequiresCustomer`.
- **SAL-15** weighed lines: up to 3 decimals in kg (`WEIGHED_QTY_INVALID`).

Approvals (SAL-03/05/07/09/10): online, a caller who holds the permission approves their own action; otherwise the
named `approvedBy` user must hold it (`iam::api` `IamQueries`), else 403 `APPROVER_NOT_ALLOWED`; no approver → 422
`APPROVAL_REQUIRED`. Offline the breach is accepted and flagged. Decisions:
[ADR-025](../../docs/decisions/ADR-025-sales-modelling-choices.md).

## Events out (`contracts/asyncapi/events.yaml`)

- `SaleCompleted`: bill, number, register, shift, location, channel, customer, cashier, business date, completed at,
  mode, flags, lines (variant, unit, qty, qtyBase, stock state, prices, discounts, tax, total), tenders, totals.
- `SaleVoided`: bill, number, customer, location, register, reason, approver, grand total, tenders and voided at.
  Inventory reverses the movements it recorded for the bill, so no lines travel.
- `SaleReturned`: credit note, bill, reason, lines (bill line, variant, qtyBase, stock state, amount, tax, restock) and
  refund tenders.

Events in: catalog `VariantCreated`/`VariantUpdated`/`VariantDeactivated` (with their packaging units) into
`sale_item`, party `PartyCreated`/`PartyUpdated` (with the credit policy, no contact details) into `customer_ref`
(version-guarded, idempotent).

## Public API for other modules

- `api.events.*`: the events above.
- `api.SalesQueries`: registers for sync and documents.
- `api.spi.CreditExposureProvider`: finance replaces sales' own fallback (Σ CREDIT tenders less CREDIT refunds) in
  phase 04 (ADR-027).
- Sales implements tenant's `BusinessActivityProbe` (TEN-02): CONFIRMED once the tenant has any bill, which locks
  its currency and time zone for everyone (ADR-027).

Sales reads `tenant::api` (settings, locations, numbering), `iam::api` (approvers, devices), `catalog::api`,
`party::api` and `pricing::api`. It never calls payments, inventory or finance.

## Endpoints

| Area | Permission |
| --- | --- |
| registers: list, get, create, update | `sales:admin` |
| shifts: open, close | `sales:create` |
| bills: create, update, complete | `sales:create` |
| bills: void | `sales:void` |
| bills: returns | `sales:return` |
| list, get bills and credit notes | `sales:read` |

Requests: [`http/sales.http`](../../http/sales.http).
