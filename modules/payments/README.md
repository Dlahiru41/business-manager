# Payments module

Money taken for bills and on customers' accounts, refunds, cash drawers from float to reconciliation, and daily
totals by method (phase 03, PAY-01..PAY-06). Contract: [`contracts/openapi/payments.yaml`](../../contracts/openapi/payments.yaml).

## Model

| Part | Kept as | Rules |
| --- | --- | --- |
| `Payment` | `payment` ledger (INSERT/SELECT only) | one per bill tender other than CREDIT (unique tender id) or account collection; amount > 0; never changed |
| `Refund` | `refund` ledger | against one payment; Σ refunds of a payment ≤ its amount (`REFUND_EXCEEDS_PAYMENT`); never changed |
| `CashMovement` | `cash_movement` ledger | FLOAT, SALE, REFUND, PAID_OUT, PAID_IN, DROP; positive amounts, the type gives the direction |
| `DrawerSession` | `drawer_session` + `drawer_denomination` | OPEN → COUNTED (recount allowed) → RECONCILED; one per register until reconciled |

`refunded` on a payment is the sum of its refund rows; nothing in a ledger is updated. Refunds of one payment are
serialised with a transaction-scoped advisory lock, since ledger rows are never row-locked.

## Rules

- **PAY-01** `SaleCompleted` → one payment per tender other than CREDIT (CREDIT is owed, finance's business). Cash adds a
  SALE movement to the register's drawer (OPEN or COUNTED); without one the payment is still recorded, logged at WARN
  and counted (`bmp.payments.cash_outside_drawer`).
- **PAY-02** `POST /payments` with a customer and no bill: an ACCOUNT payment. Cash needs `registerId` with an OPEN
  drawer (`DRAWER_NOT_OPEN`) and covers the amount (`TENDERED_TOO_LOW`); CARD and BANK_TRANSFER carry a reference unless
  the sales setting `tenderReferenceRequired` is false (`REFERENCE_REQUIRED`). Cash collections are PAID_IN movements.
- **PAY-03** `POST /payments/{id}/refunds`: at most what is left of the payment; cash comes out of an OPEN drawer of the
  register given or the payment's. For returns (`SaleReturned`) each refund tender other than CREDIT is taken from the
  bill's payments, those of its method first; for voids (`SaleVoided`) every payment is given back in full. A return
  or void whose sale is not recorded yet is retried (`OutOfOrderEventException`), never parked.
- **PAY-04** drawers: float, paid-outs, paid-ins and drops while OPEN; `expected = float + sales − refunds − paidOut +
  paidIn − drops`; a count by denomination (tenant setting `payments.cashDenominations`, LKR notes and coins by
  default, `DENOMINATION_UNKNOWN`) gives `variance = counted − expected`; reconciling recomputes it, publishes
  `DrawerReconciled` and, above `drawerVarianceAlertAmount`, logs at WARN and counts
  `bmp.payments.drawer_variance_alerts`. A cashier operates the drawer they opened (`DRAWER_NOT_YOURS`); holders of
  `payments:reconcile` operate any.
- **PAY-05** references and reasons that look like a card number are refused (`CARD_DATA_NOT_ALLOWED`).
- **PAY-06** `GET /payments/summary?date=&registerId=`: payments captured and refunds given on the business date, by
  method, and the net.

Decisions: [ADR-028](../../docs/decisions/ADR-028-payments-modelling-choices.md).

## Events

Out (`contracts/asyncapi/events.yaml`): `PaymentCaptured`, `PaymentRefunded` (with the credit note of a return),
`DrawerReconciled`. In: sales `SaleCompleted`, `SaleReturned`, `SaleVoided` (inbox-guarded, consumer
`payments.SalePaymentsListener`).

## Public API for other modules

- `api.PaymentCommands` (sync, SYN-04): open a drawer, record a paid-out/paid-in/drop, count a drawer; each in its own
  transaction and idempotent on the till's ids.
- `api.PaymentQueries`: a register's active drawer and a drawer by id, with the expected cash.
- `api.events.*`: the events above.

Payments reads `tenant::api` (settings, business date), `sales::api` (registers and events) and `party::api`
(customers of account collections). Sales never calls payments.

## Endpoints

| Area | Permission |
| --- | --- |
| list, get payments, daily summary, drawers and expected cash | `payments:read` |
| account collections | `payments:capture` |
| refunds | `payments:refund` |
| open a drawer, cash movements, count | `drawer:operate-own` |
| reconcile | `payments:reconcile` |

Requests: [`http/payments.http`](../../http/payments.http).
