-- Procurement module schema (docs/reference/data-model.md, section procurement; phase 02, PRO-01..PRO-07).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
GRANT USAGE ON SCHEMA procurement TO bmp_app;

-- PO and GRN numbers per tenant (PRO-07, ADR-012).
CREATE TABLE procurement.number_sequence
(
    tenant_id  uuid   NOT NULL,
    scope      text   NOT NULL,
    prefix     text   NOT NULL,
    next_value bigint NOT NULL,
    CONSTRAINT pk_number_sequence PRIMARY KEY (tenant_id, scope)
);
ALTER TABLE procurement.number_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE procurement.number_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procurement.number_sequence
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON procurement.number_sequence TO bmp_app;

-- Purchase orders (PRO-01).
CREATE TABLE procurement.purchase_order
(
    id           uuid        NOT NULL,
    tenant_id    uuid        NOT NULL,
    number       text        NOT NULL,
    supplier_id  uuid        NOT NULL,
    location_id  uuid        NOT NULL,
    status       text        NOT NULL,
    expected_on  date,
    notes        text,
    currency     text        NOT NULL,
    sent_at      timestamptz,
    cancelled_at timestamptz,
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    CONSTRAINT pk_purchase_order PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_purchase_order_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT ck_purchase_order_status CHECK (status IN ('DRAFT', 'SENT', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED'))
);
CREATE INDEX ix_purchase_order_tenant_supplier ON procurement.purchase_order (tenant_id, supplier_id, id);
ALTER TABLE procurement.purchase_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE procurement.purchase_order FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procurement.purchase_order
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON procurement.purchase_order TO bmp_app;

CREATE TABLE procurement.purchase_order_line
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL DEFAULT platform.current_tenant_id(),
    po_id             uuid           NOT NULL,
    line_no           integer,
    variant_id        uuid           NOT NULL,
    packaging_unit_id uuid           NOT NULL,
    qty               numeric(20, 6) NOT NULL,
    unit_cost         numeric(19, 6) NOT NULL,
    received_qty      numeric(20, 6) NOT NULL DEFAULT 0,
    CONSTRAINT pk_purchase_order_line PRIMARY KEY (id),
    CONSTRAINT fk_purchase_order_line_order FOREIGN KEY (tenant_id, po_id)
        REFERENCES procurement.purchase_order (tenant_id, id),
    CONSTRAINT ck_purchase_order_line_qty CHECK (qty > 0 AND unit_cost >= 0)
);
CREATE INDEX ix_purchase_order_line_tenant_order ON procurement.purchase_order_line (tenant_id, po_id);
ALTER TABLE procurement.purchase_order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE procurement.purchase_order_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procurement.purchase_order_line
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON procurement.purchase_order_line TO bmp_app;

-- Goods receipts (PRO-02, PRO-03): posted receipts never change (checked by the aggregate).
CREATE TABLE procurement.goods_receipt
(
    id                  uuid        NOT NULL,
    tenant_id           uuid        NOT NULL,
    number              text        NOT NULL,
    supplier_id         uuid        NOT NULL,
    location_id         uuid        NOT NULL,
    po_id               uuid,
    corrects_receipt_id uuid,
    status              text        NOT NULL,
    received_at         timestamptz NOT NULL,
    notes               text,
    currency            text        NOT NULL,
    invoiced            boolean     NOT NULL DEFAULT false,
    posted_at           timestamptz,
    posted_by           uuid,
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    created_by          uuid,
    updated_at          timestamptz NOT NULL,
    updated_by          uuid,
    CONSTRAINT pk_goods_receipt PRIMARY KEY (id),
    CONSTRAINT uk_goods_receipt_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_goods_receipt_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT fk_goods_receipt_order FOREIGN KEY (tenant_id, po_id) REFERENCES procurement.purchase_order (tenant_id, id),
    CONSTRAINT fk_goods_receipt_corrects FOREIGN KEY (tenant_id, corrects_receipt_id)
        REFERENCES procurement.goods_receipt (tenant_id, id),
    CONSTRAINT ck_goods_receipt_status CHECK (status IN ('DRAFT', 'POSTED', 'CANCELLED'))
);
CREATE INDEX ix_goods_receipt_tenant_supplier ON procurement.goods_receipt (tenant_id, supplier_id, id);
ALTER TABLE procurement.goods_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE procurement.goods_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procurement.goods_receipt
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON procurement.goods_receipt TO bmp_app;

CREATE TABLE procurement.goods_receipt_line
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL DEFAULT platform.current_tenant_id(),
    receipt_id        uuid           NOT NULL,
    line_no           integer,
    po_line_id        uuid,
    variant_id        uuid           NOT NULL,
    packaging_unit_id uuid           NOT NULL,
    qty               numeric(20, 6) NOT NULL,
    qty_base          numeric(20, 6) NOT NULL,
    stock_state       text           NOT NULL,
    unit_cost         numeric(19, 6) NOT NULL,
    unit_cost_base    numeric(19, 6) NOT NULL,
    line_total        numeric(19, 4) NOT NULL,
    batch_code        text,
    expires_on        date,
    CONSTRAINT pk_goods_receipt_line PRIMARY KEY (id),
    CONSTRAINT fk_goods_receipt_line_receipt FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES procurement.goods_receipt (tenant_id, id),
    CONSTRAINT ck_goods_receipt_line_qty CHECK (qty <> 0 AND unit_cost >= 0)
);
CREATE INDEX ix_goods_receipt_line_tenant_receipt ON procurement.goods_receipt_line (tenant_id, receipt_id);
ALTER TABLE procurement.goods_receipt_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE procurement.goods_receipt_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procurement.goods_receipt_line
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON procurement.goods_receipt_line TO bmp_app;

-- Supplier invoices (PRO-05): the supplier's reference is unique per supplier.
CREATE TABLE procurement.supplier_invoice
(
    id           uuid           NOT NULL,
    tenant_id    uuid           NOT NULL,
    supplier_id  uuid           NOT NULL,
    supplier_ref text           NOT NULL,
    invoice_date date           NOT NULL,
    due_date     date           NOT NULL,
    subtotal     numeric(19, 4) NOT NULL,
    tax          numeric(19, 4) NOT NULL,
    currency     text           NOT NULL,
    status       text           NOT NULL,
    posted_at    timestamptz,
    version      bigint         NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL,
    created_by   uuid,
    updated_at   timestamptz    NOT NULL,
    updated_by   uuid,
    CONSTRAINT pk_supplier_invoice PRIMARY KEY (id),
    CONSTRAINT uk_supplier_invoice_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_supplier_invoice_tenant_supplier_ref UNIQUE (tenant_id, supplier_id, supplier_ref),
    CONSTRAINT ck_supplier_invoice_status CHECK (status IN ('DRAFT', 'POSTED')),
    CONSTRAINT ck_supplier_invoice_amounts CHECK (subtotal >= 0 AND tax >= 0)
);
ALTER TABLE procurement.supplier_invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE procurement.supplier_invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procurement.supplier_invoice
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON procurement.supplier_invoice TO bmp_app;

CREATE TABLE procurement.invoice_receipt_match
(
    tenant_id      uuid           NOT NULL DEFAULT platform.current_tenant_id(),
    invoice_id     uuid           NOT NULL,
    receipt_id     uuid           NOT NULL,
    receipt_number text           NOT NULL,
    amount         numeric(19, 4) NOT NULL,
    CONSTRAINT pk_invoice_receipt_match PRIMARY KEY (tenant_id, invoice_id, receipt_id),
    CONSTRAINT fk_invoice_receipt_match_invoice FOREIGN KEY (tenant_id, invoice_id)
        REFERENCES procurement.supplier_invoice (tenant_id, id),
    CONSTRAINT fk_invoice_receipt_match_receipt FOREIGN KEY (tenant_id, receipt_id)
        REFERENCES procurement.goods_receipt (tenant_id, id)
);
ALTER TABLE procurement.invoice_receipt_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE procurement.invoice_receipt_match FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON procurement.invoice_receipt_match
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON procurement.invoice_receipt_match TO bmp_app;
