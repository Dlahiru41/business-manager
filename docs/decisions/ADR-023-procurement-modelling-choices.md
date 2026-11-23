# ADR-023: Procurement modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-23
- Phase / task: 02 / T02-08

## Context
PRO-01..PRO-07 fix the documents (purchase orders, goods receipts, supplier invoices), their numbers and the
`GoodsReceived` hand-over to inventory, but leave returns, over-receipts, due dates and the reorder hand-over open.
Each choice below is the most conservative option that keeps stock, money and the audit trail correct (CLAUDE.md
§1.3).

## Decisions
1. **A return is its own receipt with negative lines** that names the posted delivery it corrects
   (`correctsReceiptId`), as PRO-02 says. `correctsReceiptId` is required with negative lines and refused with
   positive ones (`CORRECTION_TARGET_INVALID`); lines of one receipt are all positive or all negative
   (`MIXED_LINE_SIGNS`). Only a posted delivery of the same supplier and location can be corrected, never a return.
2. **A return sends back at most what the delivery brought in, less earlier posted returns**, per variant in base
   units (so trays may go back for eggs received by the piece). It is checked when the return is drafted and again
   when it is posted, under a row lock on the corrected receipt, so two returns cannot both take the last units.
3. **Returns need `procurement:correct`** on create, update and post (`CORRECTION_PERMISSION_REQUIRED`, 403). The
   STOREKEEPER role has `procurement:receive` but not `procurement:correct`, so it receives deliveries but cannot send
   goods back.
4. **Over-receipt is allowed.** A posted delivery adds to the order lines' received quantities; a line is complete
   when received ≥ ordered, and the order is `RECEIVED` when every line is. Shops accept a free extra tray rather than
   turn it away; the received quantity shows the difference. Returns do not reduce received quantities: the order
   records what arrived, the return what went back.
5. **Order checks happen when the receipt is drafted and when it is posted.** A delivery may only name an open order
   (`SENT` or `PARTIALLY_RECEIVED`) of the same supplier and location, and only that order's lines with the same
   variant and packaging unit (`PURCHASE_ORDER_MISMATCH`). If the order is cancelled meanwhile, posting fails
   (`PURCHASE_ORDER_STATE_INVALID`). Posting locks the order, so concurrent receipts against it add up. Posting also
   re-checks expiry dates against the catalog's current tracking (`EXPIRY_REQUIRED`).
6. **Supplier invoices compare the subtotal (before tax) with the receipts' value.** `priceVariance = subtotal −
   Σ receipt totals`; tax is not price. A receipt is invoiced once: creating an invoice refuses an invoiced receipt,
   and posting re-checks under row locks so the second of two drafts naming the same receipt fails
   (`RECEIPT_ALREADY_INVOICED`). The supplier's reference is unique per supplier (`SUPPLIER_INVOICE_REF_TAKEN`). The due
   date defaults to the invoice date plus the supplier's payment terms (PTY-03).
7. **Numbers are taken when a document is created**, so a cancelled draft leaves a gap (`GRN-000003` cancelled, the
   next is `GRN-000004`). Gaps are visible and explained by the cancelled document, which is kept. Prefixes come from
   the tenant's `numbering` settings (`PO-`, `GRN-` by default).
8. **The reorder SPI default is a `@Fallback` bean** returning no suggestions, so inventory's implementation replaces
   it without procurement knowing about inventory (PRO-06). Suggestions without a preferred supplier or without a
   buyable unit are returned as `skippedVariantIds`; quantities are rounded up to whole buyable units; the cost per
   unit is the last cost per base unit times the unit size (0 when unknown).

## Consequences
- Inventory receives one `GoodsReceived` per posted receipt, deliveries and returns alike, with signed quantities and
  `correctsReceiptId` on returns.
- STOREKEEPER cannot read receipts (`procurement:read`); see the open question in `docs/PROGRESS.md`.
