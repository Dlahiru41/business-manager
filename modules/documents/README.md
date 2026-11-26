# Documents module

Receipts rendered to ESC/POS, PDF or text, print agents at the shop's locations, and print jobs for reprints
(phase 03, DOC-01..DOC-06). Contract: [`contracts/openapi/documents.yaml`](../../contracts/openapi/documents.yaml).
The rendering itself is [`libs/receipt-renderer`](../../libs/receipt-renderer) (pure Java, no Spring).

## Rules

- **DOC-01** `POST /documents/receipts:render` renders a bill (`billId`) or a receipt the caller sends (`receipt`,
  a till's offline bill) as `ESCPOS` (58 mm: 32 columns, 80 mm: 48), `PDF` or `TEXT`.
- **DOC-02** a receipt shows the shop header, address, phone, tax number, bill number, local date and time, cashier,
  register, lines (name, qty and unit, unit price, line total, discount), discount, tax, total, tenders, change, the
  customer of a credit bill, the footer, a COPY banner on reprints and an optional QR code with the bill id.
- **DOC-03** rows in Sinhala, Tamil or any non-ASCII script are drawn with Java2D and sent as ESC/POS raster images
  (`GS v 0`); Latin rows use the printer's text mode. Fonts: see ADR-030 §1.
- **DOC-04** the cash drawer opens (`ESC p`) after a cash tender unless the template says
  `"openDrawerOnCash": false`.
- **DOC-05** `POST /bills/{billId}/reprint` queues a COPY for the agent named or the location's agent; agents
  long-poll `GET /print-agents/{id}/jobs:next` (up to 30 s) and report `POST /print-jobs/{id}/result`. A job without
  an answer for a minute is handed out again.
- **DOC-06** `GET /print-agents/{id}/template` is the tenant's default receipt template with its version; agents
  cache it to print offline, and tills get it as the sync dataset `receiptTemplate`.

Decisions: [ADR-030](../../docs/decisions/ADR-030-receipts-and-print-jobs.md).

## Data

`documents.print_agent` (name, location, printer model, paper width, last seen and printer status) and
`documents.print_job` (agent, document type, source bill, the receipt as JSON, template version, status, attempts,
error). Both under row-level security.

## Events

None in or out.

## Public API for other modules

`api.DocumentsQueries.receiptTemplate()` (used by sync). Documents reads bills through `sales.api.SalesQueries` and
the template, tenant and tax settings through `tenant.api.TenantQueries`.

## Endpoints

| Area | Permission |
| --- | --- |
| receipts:render | `documents:render` |
| print agents, their template, jobs:next, job results | `documents:print` (DEVICE role, owners) |
| reprint a bill | `sales:read` |

Requests: [`http/documents.http`](../../http/documents.http).
