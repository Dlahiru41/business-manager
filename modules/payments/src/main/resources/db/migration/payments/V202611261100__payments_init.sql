-- Payments module schema (docs/reference/data-model.md, section payments; phase 03, PAY-01..PAY-06).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
-- payment, refund and cash_movement are ledgers: SELECT and INSERT only, corrections are new rows.
GRANT USAGE ON SCHEMA payments TO bmp_app;

-- PAY-04: a register's cash drawer from its float to its reconciliation.
CREATE TABLE payments.drawer_session
(
    id              uuid           NOT NULL,
    tenant_id       uuid           NOT NULL,
    register_id     uuid           NOT NULL,
    opened_by       uuid,
    opening_float   numeric(19, 4) NOT NULL,
    counted_cash    numeric(19, 4),
    expected_cash   numeric(19, 4),
    variance        numeric(19, 4),
    status          text           NOT NULL,
    opened_at       timestamptz    NOT NULL,
    counted_at      timestamptz,
    counted_by      uuid,
    reconciled_at   timestamptz,
    reconciled_by   uuid,
    reconcile_note  text,
    currency        text           NOT NULL,
    created_at      timestamptz    NOT NULL,
    created_by      uuid,
    updated_at      timestamptz    NOT NULL,
    updated_by      uuid,
    version         bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_drawer_session PRIMARY KEY (id),
    CONSTRAINT uk_drawer_session_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_drawer_session_status CHECK (status IN ('OPEN', 'COUNTED', 'RECONCILED')),
    CONSTRAINT ck_drawer_session_float CHECK (opening_float >= 0),
    CONSTRAINT ck_drawer_session_currency CHECK (currency ~ '^[A-Z]{3}$')
);
-- PAY-04: at most one drawer session per register until it is reconciled.
CREATE UNIQUE INDEX uk_drawer_session_open_per_register ON payments.drawer_session (tenant_id, register_id)
    WHERE status IN ('OPEN', 'COUNTED');
CREATE INDEX ix_drawer_session_tenant_register ON payments.drawer_session (tenant_id, register_id, opened_at);

-- PAY-04: the last count of a drawer by denomination; a recount replaces it.
CREATE TABLE payments.drawer_denomination
(
    tenant_id         uuid           NOT NULL DEFAULT platform.current_tenant_id(),
    drawer_session_id uuid           NOT NULL,
    value             numeric(19, 4) NOT NULL,
    count             integer        NOT NULL,
    CONSTRAINT pk_drawer_denomination PRIMARY KEY (drawer_session_id, value),
    CONSTRAINT fk_drawer_denomination_session FOREIGN KEY (drawer_session_id) REFERENCES payments.drawer_session (id),
    CONSTRAINT ck_drawer_denomination_value CHECK (value > 0),
    CONSTRAINT ck_drawer_denomination_count CHECK (count >= 0)
);

-- PAY-01, PAY-02: money taken, one row per bill tender (not CREDIT) or account collection; never changed.
CREATE TABLE payments.payment
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    kind              text           NOT NULL,
    bill_id           uuid,
    tender_id         uuid,
    customer_id       uuid,
    method            text           NOT NULL,
    amount            numeric(19, 4) NOT NULL,
    currency          text           NOT NULL,
    tendered          numeric(19, 4),
    change_given      numeric(19, 4),
    reference         text,
    status            text           NOT NULL DEFAULT 'CAPTURED',
    register_id       uuid,
    drawer_session_id uuid,
    device_id         uuid,
    business_date     date           NOT NULL,
    captured_at       timestamptz    NOT NULL,
    recorded_at       timestamptz    NOT NULL,
    created_by        uuid,
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT uk_payment_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_payment_drawer_session FOREIGN KEY (tenant_id, drawer_session_id)
        REFERENCES payments.drawer_session (tenant_id, id),
    CONSTRAINT ck_payment_kind CHECK (kind IN ('BILL', 'ACCOUNT')),
    CONSTRAINT ck_payment_kind_source CHECK ((kind = 'BILL') = (bill_id IS NOT NULL AND tender_id IS NOT NULL)),
    CONSTRAINT ck_payment_method CHECK (method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'MOBILE_WALLET', 'CHEQUE')),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_status CHECK (status = 'CAPTURED'),
    CONSTRAINT ck_payment_currency CHECK (currency ~ '^[A-Z]{3}$')
);
-- PAY-01: one payment per tender, however often the sale is delivered.
CREATE UNIQUE INDEX uk_payment_tenant_tender ON payments.payment (tenant_id, tender_id) WHERE tender_id IS NOT NULL;
CREATE INDEX ix_payment_tenant_bill ON payments.payment (tenant_id, bill_id) WHERE bill_id IS NOT NULL;
CREATE INDEX ix_payment_tenant_date ON payments.payment (tenant_id, business_date, method);

-- PAY-03: money given back against a payment; never changed.
CREATE TABLE payments.refund
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    payment_id        uuid           NOT NULL,
    amount            numeric(19, 4) NOT NULL,
    method            text           NOT NULL,
    currency          text           NOT NULL,
    reason            text           NOT NULL,
    credit_note_id    uuid,
    source_event_id   uuid,
    register_id       uuid,
    drawer_session_id uuid,
    business_date     date           NOT NULL,
    created_at        timestamptz    NOT NULL,
    created_by        uuid,
    CONSTRAINT pk_refund PRIMARY KEY (id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (tenant_id, payment_id) REFERENCES payments.payment (tenant_id, id),
    CONSTRAINT fk_refund_drawer_session FOREIGN KEY (tenant_id, drawer_session_id)
        REFERENCES payments.drawer_session (tenant_id, id),
    CONSTRAINT ck_refund_method CHECK (method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'MOBILE_WALLET', 'CHEQUE')),
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_currency CHECK (currency ~ '^[A-Z]{3}$')
);
CREATE INDEX ix_refund_tenant_payment ON payments.refund (tenant_id, payment_id);
CREATE INDEX ix_refund_tenant_date ON payments.refund (tenant_id, business_date, method);

-- PAY-04: cash in and out of a drawer; amounts are positive, the type gives the direction. Never changed.
CREATE TABLE payments.cash_movement
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    drawer_session_id uuid           NOT NULL,
    type              text           NOT NULL,
    amount            numeric(19, 4) NOT NULL,
    reason            text,
    source_type       text           NOT NULL,
    source_id         uuid,
    occurred_at       timestamptz    NOT NULL,
    recorded_at       timestamptz    NOT NULL,
    created_by        uuid,
    CONSTRAINT pk_cash_movement PRIMARY KEY (id),
    CONSTRAINT fk_cash_movement_session FOREIGN KEY (tenant_id, drawer_session_id)
        REFERENCES payments.drawer_session (tenant_id, id),
    CONSTRAINT ck_cash_movement_type CHECK (type IN ('FLOAT', 'SALE', 'REFUND', 'PAID_OUT', 'PAID_IN', 'DROP')),
    CONSTRAINT ck_cash_movement_amount CHECK (amount >= 0)
);
CREATE INDEX ix_cash_movement_tenant_session ON payments.cash_movement (tenant_id, drawer_session_id, occurred_at);

DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['drawer_session', 'drawer_denomination', 'payment', 'refund', 'cash_movement']
            LOOP
                EXECUTE format('ALTER TABLE payments.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE payments.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON payments.%I USING (tenant_id = platform.current_tenant_id())'
                                   || ' WITH CHECK (tenant_id = platform.current_tenant_id())', t);
            END LOOP;
    END
$$;

GRANT SELECT, INSERT, UPDATE ON payments.drawer_session TO bmp_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON payments.drawer_denomination TO bmp_app;
GRANT SELECT, INSERT ON payments.payment, payments.refund, payments.cash_movement TO bmp_app;
