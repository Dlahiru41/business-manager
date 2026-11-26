-- Sales module schema (docs/reference/data-model.md, section sales; phase 03, SAL-01..SAL-15).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
GRANT USAGE ON SCHEMA sales TO bmp_app;

-- Credit note numbers per tenant (SAL-10, ADR-012).
CREATE TABLE sales.number_sequence
(
    tenant_id  uuid   NOT NULL,
    scope      text   NOT NULL,
    prefix     text   NOT NULL,
    next_value bigint NOT NULL,
    CONSTRAINT pk_number_sequence PRIMARY KEY (tenant_id, scope)
);

-- Replica of sellable variants from catalog events: what a bill line needs without asking the catalog (SAL-08).
CREATE TABLE sales.sale_item
(
    tenant_id         uuid        NOT NULL,
    variant_id        uuid        NOT NULL,
    product_id        uuid        NOT NULL,
    sku               text        NOT NULL,
    name              text        NOT NULL,
    base_unit         text        NOT NULL,
    weighable         boolean     NOT NULL,
    packaging_units   jsonb       NOT NULL DEFAULT '[]',
    status            text        NOT NULL,
    aggregate_version bigint      NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT pk_sale_item PRIMARY KEY (tenant_id, variant_id)
);

-- Replica of customers from party events: credit terms for credit sales (SAL-07). No contact details.
CREATE TABLE sales.customer_ref
(
    tenant_id         uuid           NOT NULL,
    party_id          uuid           NOT NULL,
    type              text           NOT NULL,
    display_name      text           NOT NULL,
    customer_group_id uuid,
    status            text           NOT NULL,
    credit_allowed    boolean        NOT NULL,
    credit_limit      numeric(19, 4) NOT NULL,
    aggregate_version bigint         NOT NULL,
    updated_at        timestamptz    NOT NULL,
    CONSTRAINT pk_customer_ref PRIMARY KEY (tenant_id, party_id)
);

CREATE TABLE sales.register
(
    id          uuid        NOT NULL,
    tenant_id   uuid        NOT NULL,
    code        text        NOT NULL,
    name        text,
    location_id uuid        NOT NULL,
    device_id   uuid,
    bill_prefix text        NOT NULL,
    status      text        NOT NULL,
    last_number bigint      NOT NULL DEFAULT 0,
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    created_by  uuid,
    updated_at  timestamptz NOT NULL,
    updated_by  uuid,
    CONSTRAINT pk_register PRIMARY KEY (id),
    CONSTRAINT uk_register_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_register_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uk_register_tenant_prefix UNIQUE (tenant_id, bill_prefix),
    CONSTRAINT ck_register_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE sales.shift
(
    id          uuid        NOT NULL,
    tenant_id   uuid        NOT NULL,
    register_id uuid        NOT NULL,
    cashier_id  uuid,
    status      text        NOT NULL,
    opened_at   timestamptz NOT NULL,
    closed_at   timestamptz,
    offline     boolean     NOT NULL,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_shift PRIMARY KEY (id),
    CONSTRAINT fk_shift_register FOREIGN KEY (tenant_id, register_id) REFERENCES sales.register (tenant_id, id),
    CONSTRAINT ck_shift_status CHECK (status IN ('OPEN', 'CLOSED'))
);
-- SAL-12: at most one OPEN shift per register.
CREATE UNIQUE INDEX uk_shift_open_per_register ON sales.shift (tenant_id, register_id) WHERE status = 'OPEN' AND NOT offline;
CREATE INDEX ix_shift_tenant_register_opened ON sales.shift (tenant_id, register_id, opened_at);

CREATE TABLE sales.bill
(
    id                    uuid           NOT NULL,
    tenant_id             uuid           NOT NULL,
    number                text           NOT NULL,
    register_id           uuid           NOT NULL,
    shift_id              uuid,
    location_id           uuid           NOT NULL,
    device_id             uuid,
    channel               text           NOT NULL,
    customer_id           uuid,
    price_list_id         uuid,
    price_list_version    integer,
    status                text           NOT NULL,
    discount_type         text,
    discount_value        numeric(19, 4),
    discount_reason       text,
    discount_approved_by  uuid,
    bill_discount_amount  numeric(19, 4) NOT NULL,
    subtotal              numeric(19, 4) NOT NULL,
    discount_total        numeric(19, 4) NOT NULL,
    tax_total             numeric(19, 4) NOT NULL,
    grand_total           numeric(19, 4) NOT NULL,
    device_subtotal       numeric(19, 4),
    device_discount_total numeric(19, 4),
    device_tax_total      numeric(19, 4),
    device_grand_total    numeric(19, 4),
    currency              text           NOT NULL,
    business_date         date,
    completed_at          timestamptz,
    cashier_id            uuid,
    offline               boolean        NOT NULL,
    flags                 text[]         NOT NULL DEFAULT '{}',
    void_reason           text,
    voided_at             timestamptz,
    voided_by             uuid,
    void_approved_by      uuid,
    version               bigint         NOT NULL DEFAULT 0,
    created_at            timestamptz    NOT NULL,
    created_by            uuid,
    updated_at            timestamptz    NOT NULL,
    updated_by            uuid,
    CONSTRAINT pk_bill PRIMARY KEY (id),
    CONSTRAINT uk_bill_tenant_id UNIQUE (tenant_id, id),
    -- SAL-01: one bill per number; the same number with another id is BILL_NUMBER_CONFLICT.
    CONSTRAINT uk_bill_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT fk_bill_register FOREIGN KEY (tenant_id, register_id) REFERENCES sales.register (tenant_id, id),
    CONSTRAINT ck_bill_channel CHECK (channel IN ('RETAIL', 'WHOLESALE')),
    CONSTRAINT ck_bill_status CHECK (status IN ('DRAFT', 'COMPLETED', 'VOIDED', 'PARTIALLY_RETURNED', 'RETURNED'))
);
CREATE INDEX ix_bill_tenant_date ON sales.bill (tenant_id, business_date);
CREATE INDEX ix_bill_tenant_customer ON sales.bill (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX ix_bill_tenant_register ON sales.bill (tenant_id, register_id, number);

CREATE TABLE sales.bill_line
(
    id                      uuid           NOT NULL,
    tenant_id               uuid           NOT NULL,
    bill_id                 uuid           NOT NULL,
    line_no                 integer,
    variant_id              uuid           NOT NULL,
    packaging_unit_id       uuid           NOT NULL,
    description             text           NOT NULL,
    qty                     numeric(20, 6) NOT NULL,
    qty_base                numeric(20, 6) NOT NULL,
    stock_state             text           NOT NULL,
    weighed                 boolean        NOT NULL,
    unit_price              numeric(19, 4) NOT NULL,
    line_discount           numeric(19, 4) NOT NULL,
    rule_discount           numeric(19, 4) NOT NULL,
    allocated_bill_discount numeric(19, 4) NOT NULL,
    tax_amount              numeric(19, 4) NOT NULL,
    line_total              numeric(19, 4) NOT NULL,
    price_source            text,
    returned_qty            numeric(20, 6) NOT NULL DEFAULT 0,
    returned_amount         numeric(19, 4) NOT NULL DEFAULT 0,
    returned_tax            numeric(19, 4) NOT NULL DEFAULT 0,
    CONSTRAINT pk_bill_line PRIMARY KEY (id),
    CONSTRAINT fk_bill_line_bill FOREIGN KEY (tenant_id, bill_id) REFERENCES sales.bill (tenant_id, id),
    CONSTRAINT ck_bill_line_qty CHECK (qty > 0 AND qty_base > 0 AND returned_qty >= 0 AND returned_qty <= qty)
);
CREATE INDEX ix_bill_line_tenant_bill ON sales.bill_line (tenant_id, bill_id);

CREATE TABLE sales.bill_tender
(
    id           uuid           NOT NULL,
    tenant_id    uuid           NOT NULL,
    bill_id      uuid           NOT NULL,
    tender_no    integer,
    method       text           NOT NULL,
    amount       numeric(19, 4) NOT NULL,
    tendered     numeric(19, 4),
    change_given numeric(19, 4),
    reference    text,
    CONSTRAINT pk_bill_tender PRIMARY KEY (id),
    CONSTRAINT fk_bill_tender_bill FOREIGN KEY (tenant_id, bill_id) REFERENCES sales.bill (tenant_id, id),
    CONSTRAINT ck_bill_tender_method CHECK (method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'MOBILE_WALLET', 'CHEQUE',
                                                       'CREDIT')),
    CONSTRAINT ck_bill_tender_amount CHECK (amount > 0)
);
CREATE INDEX ix_bill_tender_tenant_bill ON sales.bill_tender (tenant_id, bill_id);

CREATE TABLE sales.credit_note
(
    id          uuid           NOT NULL,
    tenant_id   uuid           NOT NULL,
    number      text           NOT NULL,
    bill_id     uuid           NOT NULL,
    reason      text           NOT NULL,
    total       numeric(19, 4) NOT NULL,
    tax_total   numeric(19, 4) NOT NULL,
    status      text           NOT NULL,
    approved_by uuid,
    returned_at timestamptz    NOT NULL,
    created_at  timestamptz    NOT NULL,
    created_by  uuid,
    updated_at  timestamptz    NOT NULL,
    updated_by  uuid,
    CONSTRAINT pk_credit_note PRIMARY KEY (id),
    CONSTRAINT uk_credit_note_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_credit_note_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT fk_credit_note_bill FOREIGN KEY (tenant_id, bill_id) REFERENCES sales.bill (tenant_id, id)
);
CREATE INDEX ix_credit_note_tenant_bill ON sales.credit_note (tenant_id, bill_id);

CREATE TABLE sales.credit_note_line
(
    id             uuid           NOT NULL,
    tenant_id      uuid           NOT NULL,
    credit_note_id uuid           NOT NULL,
    line_no        integer,
    bill_line_id   uuid           NOT NULL,
    variant_id     uuid           NOT NULL,
    stock_state    text           NOT NULL,
    qty            numeric(20, 6) NOT NULL,
    qty_base       numeric(20, 6) NOT NULL,
    amount         numeric(19, 4) NOT NULL,
    tax_amount     numeric(19, 4) NOT NULL,
    restock        boolean        NOT NULL,
    CONSTRAINT pk_credit_note_line PRIMARY KEY (id),
    CONSTRAINT fk_credit_note_line_note FOREIGN KEY (tenant_id, credit_note_id)
        REFERENCES sales.credit_note (tenant_id, id)
);

CREATE TABLE sales.credit_note_refund
(
    tenant_id          uuid           NOT NULL DEFAULT platform.current_tenant_id(),
    credit_note_id     uuid           NOT NULL,
    refund_no          integer        NOT NULL,
    refund_id          uuid           NOT NULL,
    method             text           NOT NULL,
    amount             numeric(19, 4) NOT NULL,
    reference          text,
    original_tender_id uuid,
    CONSTRAINT pk_credit_note_refund PRIMARY KEY (tenant_id, credit_note_id, refund_no),
    CONSTRAINT fk_credit_note_refund_note FOREIGN KEY (tenant_id, credit_note_id)
        REFERENCES sales.credit_note (tenant_id, id)
);

-- Row-level security on every table (06 par. 10).
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['number_sequence', 'sale_item', 'customer_ref', 'register', 'shift', 'bill',
            'bill_line', 'bill_tender', 'credit_note', 'credit_note_line', 'credit_note_refund']
            LOOP
                EXECUTE format('ALTER TABLE sales.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE sales.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON sales.%I USING (tenant_id = platform.current_tenant_id())'
                                   || ' WITH CHECK (tenant_id = platform.current_tenant_id())', t);
            END LOOP;
    END
$$;

GRANT SELECT, INSERT, UPDATE ON sales.number_sequence, sales.sale_item, sales.customer_ref, sales.register,
    sales.shift, sales.bill TO bmp_app;
-- Draft lines are replaced while a bill is edited; completed bills never change (SAL-08).
GRANT SELECT, INSERT, UPDATE, DELETE ON sales.bill_line, sales.bill_tender TO bmp_app;
GRANT SELECT, INSERT ON sales.credit_note, sales.credit_note_line, sales.credit_note_refund TO bmp_app;
