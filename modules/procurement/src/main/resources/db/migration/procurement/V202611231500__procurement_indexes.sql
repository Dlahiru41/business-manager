-- Indexes on the columns receipts and invoice matches are looked up by (02 §9): receipts of an order, returns of a
-- delivery (read under lock when a return is posted) and the invoices a receipt is matched to.
CREATE INDEX ix_goods_receipt_tenant_po ON procurement.goods_receipt (tenant_id, po_id);
CREATE INDEX ix_goods_receipt_tenant_corrects ON procurement.goods_receipt (tenant_id, corrects_receipt_id);
CREATE INDEX ix_invoice_receipt_match_tenant_receipt ON procurement.invoice_receipt_match (tenant_id, receipt_id);
