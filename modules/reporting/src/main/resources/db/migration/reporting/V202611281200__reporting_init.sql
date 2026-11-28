-- Reporting module schema (docs/reference/data-model.md, section reporting; phase 04, REP-01..REP-07; ADR-033).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it. Read models are rebuilt from the
-- module's own event log, which is insert only.
GRANT USAGE ON SCHEMA reporting TO bmp_app, bmp_platform_admin;

-- REP-01: what reporting applied, in order: the source of every rebuild.
CREATE TABLE reporting.event_log
(
    tenant_id   uuid        NOT NULL,
    seq         bigint GENERATED ALWAYS AS IDENTITY,
    event_id    uuid        NOT NULL,
    fact_type   text        NOT NULL,
    payload     jsonb       NOT NULL,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT pk_event_log PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX ix_event_log_tenant_seq ON reporting.event_log (tenant_id, seq);
CREATE INDEX ix_event_log_tenant_recorded ON reporting.event_log (tenant_id, recorded_at);

CREATE TABLE reporting.fact_bill
(
    tenant_id      uuid           NOT NULL,
    bill_id        uuid           NOT NULL,
    number         text           NOT NULL,
    business_date  date           NOT NULL,
    location_id    uuid           NOT NULL,
    register_id    uuid           NOT NULL,
    channel        text           NOT NULL,
    customer_id    uuid,
    cashier_id     uuid,
    hour           integer        NOT NULL,
    completed_at   timestamptz    NOT NULL,
    grand_total    numeric(19, 4) NOT NULL,
    tax_total      numeric(19, 4) NOT NULL,
    discount_total numeric(19, 4) NOT NULL,
    status         text           NOT NULL,
    voided_on      date,
    CONSTRAINT pk_fact_bill PRIMARY KEY (tenant_id, bill_id),
    CONSTRAINT ck_fact_bill_status CHECK (status IN ('COMPLETED', 'VOIDED'))
);
CREATE INDEX ix_fact_bill_tenant_date ON reporting.fact_bill (tenant_id, business_date);
CREATE INDEX ix_fact_bill_tenant_completed ON reporting.fact_bill (tenant_id, completed_at);

-- REP-02: one entry per bill line sold, returned or voided; the sale half and the cost half land independently.
CREATE TABLE reporting.fact_sale_line
(
    tenant_id         uuid           NOT NULL,
    entry_id          uuid           NOT NULL,
    kind              text           NOT NULL,
    bill_id           uuid           NOT NULL,
    bill_line_id      uuid           NOT NULL,
    source_id         uuid           NOT NULL,
    business_date     date,
    location_id       uuid,
    channel           text,
    customer_id       uuid,
    cashier_id        uuid,
    variant_id        uuid,
    packaging_unit_id uuid,
    qty               numeric(20, 6) NOT NULL DEFAULT 0,
    qty_base          numeric(20, 6) NOT NULL DEFAULT 0,
    revenue           numeric(19, 4) NOT NULL DEFAULT 0,
    discount          numeric(19, 4) NOT NULL DEFAULT 0,
    tax               numeric(19, 4) NOT NULL DEFAULT 0,
    cost              numeric(19, 4) NOT NULL DEFAULT 0,
    has_sale          boolean        NOT NULL DEFAULT false,
    cost_known        boolean        NOT NULL DEFAULT false,
    CONSTRAINT pk_fact_sale_line PRIMARY KEY (tenant_id, entry_id),
    CONSTRAINT ck_fact_sale_line_kind CHECK (kind IN ('SALE', 'RETURN', 'VOID'))
);
CREATE INDEX ix_fact_sale_line_tenant_date ON reporting.fact_sale_line (tenant_id, business_date);
CREATE INDEX ix_fact_sale_line_tenant_bill ON reporting.fact_sale_line (tenant_id, bill_id);

CREATE TABLE reporting.fact_payment
(
    tenant_id      uuid           NOT NULL,
    entry_id       uuid           NOT NULL,
    kind           text           NOT NULL,
    business_date  date           NOT NULL,
    method         text           NOT NULL,
    amount         numeric(19, 4) NOT NULL,
    register_id    uuid,
    bill_id        uuid,
    credit_note_id uuid,
    CONSTRAINT pk_fact_payment PRIMARY KEY (tenant_id, entry_id),
    CONSTRAINT ck_fact_payment_kind CHECK (kind IN ('CAPTURE', 'REFUND'))
);
CREATE INDEX ix_fact_payment_tenant_date ON reporting.fact_payment (tenant_id, business_date);

CREATE TABLE reporting.fact_expense
(
    tenant_id     uuid           NOT NULL,
    expense_id    uuid           NOT NULL,
    business_date date           NOT NULL,
    category_id   uuid           NOT NULL,
    amount        numeric(19, 4) NOT NULL,
    CONSTRAINT pk_fact_expense PRIMARY KEY (tenant_id, expense_id)
);
CREATE INDEX ix_fact_expense_tenant_date ON reporting.fact_expense (tenant_id, business_date);

CREATE TABLE reporting.fact_drawer
(
    tenant_id     uuid           NOT NULL,
    session_id    uuid           NOT NULL,
    register_id   uuid           NOT NULL,
    business_date date           NOT NULL,
    expected      numeric(19, 4) NOT NULL,
    counted       numeric(19, 4) NOT NULL,
    variance      numeric(19, 4) NOT NULL,
    reconciled_at timestamptz    NOT NULL,
    CONSTRAINT pk_fact_drawer PRIMARY KEY (tenant_id, session_id)
);
CREATE INDEX ix_fact_drawer_tenant_date ON reporting.fact_drawer (tenant_id, business_date);

CREATE TABLE reporting.fact_stock_movement
(
    tenant_id     uuid           NOT NULL,
    movement_id   uuid           NOT NULL,
    source        text           NOT NULL,
    business_date date           NOT NULL,
    location_id   uuid           NOT NULL,
    variant_id    uuid           NOT NULL,
    reason        text,
    qty_base      numeric(20, 6) NOT NULL,
    cost_amount   numeric(19, 4) NOT NULL,
    CONSTRAINT pk_fact_stock_movement PRIMARY KEY (tenant_id, movement_id),
    CONSTRAINT ck_fact_stock_movement_source CHECK (source IN ('ADJUSTMENT', 'COUNT', 'TRANSFORMATION'))
);
CREATE INDEX ix_fact_stock_movement_tenant_date ON reporting.fact_stock_movement (tenant_id, business_date);

CREATE TABLE reporting.variant_ref
(
    tenant_id  uuid   NOT NULL,
    variant_id uuid   NOT NULL,
    product_id uuid   NOT NULL,
    sku        text   NOT NULL,
    name       text   NOT NULL,
    version    bigint NOT NULL,
    CONSTRAINT pk_variant_ref PRIMARY KEY (tenant_id, variant_id)
);

CREATE TABLE reporting.product_ref
(
    tenant_id   uuid   NOT NULL,
    product_id  uuid   NOT NULL,
    name        text   NOT NULL,
    category_id uuid,
    version     bigint NOT NULL,
    CONSTRAINT pk_product_ref PRIMARY KEY (tenant_id, product_id)
);

-- REP-07: what a return should give back in money, and what payments gave back for it.
CREATE TABLE reporting.return_watch
(
    tenant_id      uuid           NOT NULL,
    credit_note_id uuid           NOT NULL,
    bill_id        uuid,
    returned_at    timestamptz,
    expected       numeric(19, 4) NOT NULL DEFAULT 0,
    refunded       numeric(19, 4) NOT NULL DEFAULT 0,
    CONSTRAINT pk_return_watch PRIMARY KEY (tenant_id, credit_note_id)
);

-- REP-04, REP-06: a business day at a location (expenses under the nil location).
CREATE TABLE reporting.daily_summary
(
    tenant_id     uuid           NOT NULL,
    business_date date           NOT NULL,
    location_id   uuid           NOT NULL,
    revenue       numeric(19, 4) NOT NULL DEFAULT 0,
    cost          numeric(19, 4) NOT NULL DEFAULT 0,
    expenses      numeric(19, 4) NOT NULL DEFAULT 0,
    bills         integer        NOT NULL DEFAULT 0,
    CONSTRAINT pk_daily_summary PRIMARY KEY (tenant_id, business_date, location_id)
);

-- REP-07: issues the monitors found; one per kind and source.
CREATE TABLE reporting.data_quality_issue
(
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    kind        text        NOT NULL,
    source_id   uuid        NOT NULL,
    amount      numeric(19, 4),
    status      text        NOT NULL,
    detected_at timestamptz NOT NULL,
    resolved_at timestamptz,
    CONSTRAINT pk_data_quality_issue PRIMARY KEY (tenant_id, id),
    CONSTRAINT uk_data_quality_issue_kind_source UNIQUE (tenant_id, kind, source_id),
    CONSTRAINT ck_data_quality_issue_kind CHECK (kind IN ('RETURN_WITHOUT_REFUND', 'BILL_WITHOUT_STOCK_ISSUE',
                                                          'POS_CLEARING_NOT_ZERO')),
    CONSTRAINT ck_data_quality_issue_status CHECK (status IN ('OPEN', 'RESOLVED'))
);

-- REP-05: export files kept seven days behind a signed link.
CREATE TABLE reporting.export_file
(
    tenant_id    uuid        NOT NULL,
    id           uuid        NOT NULL,
    report       text        NOT NULL,
    format       text        NOT NULL,
    parameters   jsonb       NOT NULL,
    status       text        NOT NULL,
    operation_id uuid        NOT NULL,
    content      bytea,
    content_type text,
    size_bytes   bigint,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz,
    CONSTRAINT pk_export_file PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_export_file_format CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    CONSTRAINT ck_export_file_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED'))
);
CREATE INDEX ix_export_file_expires ON reporting.export_file (expires_at);

DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['event_log', 'fact_bill', 'fact_sale_line', 'fact_payment', 'fact_expense',
            'fact_drawer', 'fact_stock_movement', 'variant_ref', 'product_ref', 'return_watch', 'daily_summary',
            'data_quality_issue', 'export_file']
            LOOP
                EXECUTE format('ALTER TABLE reporting.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE reporting.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON reporting.%I USING (tenant_id = platform.current_tenant_id())'
                                   || ' WITH CHECK (tenant_id = platform.current_tenant_id())', t);
            END LOOP;
    END
$$;

GRANT SELECT, INSERT ON reporting.event_log TO bmp_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_bill, reporting.fact_sale_line, reporting.fact_payment,
    reporting.fact_expense, reporting.fact_drawer, reporting.fact_stock_movement, reporting.variant_ref,
    reporting.product_ref, reporting.return_watch, reporting.daily_summary, reporting.data_quality_issue,
    reporting.export_file TO bmp_app;
-- The data-quality and export clean-up jobs find the tenants to visit across tenants.
GRANT SELECT ON reporting.event_log, reporting.export_file TO bmp_platform_admin;
GRANT DELETE ON reporting.export_file TO bmp_platform_admin;
