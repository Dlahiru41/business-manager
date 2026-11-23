# Procurement module

Purchase orders, goods receipts (deliveries and returns) and supplier invoices of a tenant (phase 02, rules
PRO-01..PRO-07). Contract: [`contracts/openapi/procurement.yaml`](../../contracts/openapi/procurement.yaml).

## Aggregates

| Aggregate | Parts | Invariants |
| --- | --- | --- |
| `PurchaseOrder` | lines (qty, unit cost, received qty) | DRAFT → SENT → PARTIALLY_RECEIVED / RECEIVED, or CANCELLED before anything arrives; only drafts change; buyable units only; quantities > 0, costs ≥ 0 (PRO-01) |
| `GoodsReceipt` | lines (qty, qtyBase, unit cost, unit cost per base unit, line total, batch, expiry) | DRAFT → POSTED or CANCELLED; posted is final; all lines positive (delivery) or negative (return naming the receipt it corrects); a return sends back at most what arrived less earlier returns (PRO-02, PRO-03) |
| `SupplierInvoice` | matched receipts (number, value) | DRAFT → POSTED; at least one posted receipt of the same supplier, each invoiced once; supplier reference unique per supplier (PRO-05) |

`qtyBase = qty × qtyInBase` and `unitCostBase = unitCost / qtyInBase` at scale 6; line totals are rounded to the
currency (PRO-03).

## Rules

- **PRO-01** order lines use `canBuy` packaging units of active variants (`UNIT_NOT_PURCHASABLE`, `UNKNOWN_VARIANT`,
  `UNKNOWN_PACKAGING_UNIT`). A posted delivery adds to the lines' received quantities and moves the status on.
- **PRO-02** a receipt may name an open order of the same supplier and location, or none (walk-in delivery). Posting
  is a separate action. Returns need `procurement:correct` and `correctsReceiptId`.
- **PRO-03** expiry-tracked variants need `expiresOn` on every delivered line (`EXPIRY_REQUIRED`).
- **PRO-04** posting publishes `GoodsReceived`; inventory turns it into RECEIPT movements.
- **PRO-05** the price variance is the invoice subtotal less the matched receipts' value, carried on
  `SupplierInvoicePosted`. The due date defaults to the supplier's payment terms.
- **PRO-06** `POST /purchase-orders:from-reorder-list` drafts one order per preferred supplier and location from the
  `ReorderSuggestionProvider` SPI.
- **PRO-07** numbers `PO-000001`, `GRN-000001` per tenant, taken when the document is created.

Decisions: [ADR-023](../../docs/decisions/ADR-023-procurement-modelling-choices.md).

## Events out (`contracts/asyncapi/events.yaml`)

- `GoodsReceived`: receipt id and number, supplier, location, order, corrected receipt, received at, currency, total and
  lines (variant, packaging unit, qty, qtyBase, stock state, unit costs, line total, batch code, expiry). Signed: a
  return has negative quantities and totals.
- `SupplierInvoicePosted`: invoice, supplier and reference, dates, subtotal, tax, total, receipt value, price variance
  and the receipt ids.

Events in: none.

## Public API for other modules

- `api.events.*`: the events above.
- `api.spi.ReorderSuggestionProvider`: implemented by inventory, which knows stock; procurement's own fallback returns
  no suggestions. Procurement never imports inventory.

Procurement reads parties (`party::api`), variants and packaging units (`catalog::api`) and locations, currency and
numbering settings (`tenant::api`).

## Endpoints

| Area | Permission |
| --- | --- |
| list, get orders, receipts, invoices | `procurement:read` |
| orders: create, update, send, cancel, from reorder list | `procurement:order` |
| receipts: create, update, post, cancel | `procurement:receive` (+ `procurement:correct` for returns) |
| invoices: create, post | `procurement:invoice` |

Examples: [`http/procurement.http`](../../http/procurement.http).

## Persistence

Schema `procurement`: `purchase_order` (+ `purchase_order_line`), `goods_receipt` (+ `goods_receipt_line`),
`supplier_invoice` (+ `invoice_receipt_match`) and `number_sequence`, all under row-level security. Documents carry
a version for `If-Match` and optimistic locking; posting a return or an invoice locks the receipts it depends on.

## Personal data

None: suppliers are referred to by id.
