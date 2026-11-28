-- Finance module schema (docs/reference/data-model.md, section finance; phase 04, FIN-01..FIN-11; ADR-032).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
-- journal_entry, journal_line, open_item_settlement, expense, supplier_payment, supplier_payment_allocation and
-- cash_drop are ledgers: SELECT and INSERT only, corrections are new rows (reversing journals).
GRANT USAGE ON SCHEMA finance TO bmp_app, bmp_platform_admin;

-- Journal numbers JE-000001 per tenant (ADR-012).
CREATE TABLE finance.number_sequence
(
    tenant_id  uuid   NOT NULL,
    scope      text   NOT NULL,
    prefix     text   NOT NULL,
    next_value bigint NOT NULL,
    CONSTRAINT pk_number_sequence PRIMARY KEY (tenant_id, scope)
);

-- FIN-01: the chart of accounts; system_key marks the seeded accounts the posting rules use.
CREATE TABLE finance.account
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    type       text        NOT NULL,
    subtype    text,
    parent_id  uuid,
    system_key text,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT uk_account_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_account_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uk_account_tenant_system_key UNIQUE (tenant_id, system_key),
    CONSTRAINT fk_account_parent FOREIGN KEY (tenant_id, parent_id) REFERENCES finance.account (tenant_id, id),
    CONSTRAINT ck_account_type CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_account_code CHECK (code ~ '^[0-9A-Z][0-9A-Z-]{0,19}$')
);

-- FIN-02..FIN-05: one journal per source event (source_event_id unique), never changed; a reversal points back and a
-- journal is reversed at most once.
CREATE TABLE finance.journal_entry
(
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    number          text        NOT NULL,
    entry_date      date        NOT NULL,
    period          char(7)     NOT NULL,
    source_type     text        NOT NULL,
    source_id       uuid        NOT NULL,
    source_event_id uuid,
    memo            text,
    reverses_id     uuid,
    currency        text        NOT NULL,
    posted_at       timestamptz NOT NULL,
    posted_by       uuid,
    CONSTRAINT pk_journal_entry PRIMARY KEY (id),
    CONSTRAINT uk_journal_entry_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_journal_entry_tenant_number UNIQUE (tenant_id, number),
    CONSTRAINT uk_journal_entry_tenant_source_event UNIQUE (tenant_id, source_event_id),
    CONSTRAINT uk_journal_entry_tenant_reverses UNIQUE (tenant_id, reverses_id),
    CONSTRAINT fk_journal_entry_reverses FOREIGN KEY (tenant_id, reverses_id)
        REFERENCES finance.journal_entry (tenant_id, id),
    CONSTRAINT ck_journal_entry_period CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_journal_entry_period_of_date CHECK (period = to_char(entry_date, 'YYYY-MM')),
    CONSTRAINT ck_journal_entry_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_journal_entry_source_type CHECK (source_type IN (
        'SALE', 'BILL_PAYMENT', 'COLLECTION', 'STOCK_ISSUE', 'SALE_RETURN', 'PAYMENT_REFUND', 'SALE_VOID',
        'GOODS_RECEIPT', 'STOCK_RECEIPT', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'STOCK_ADJUSTMENT', 'STOCK_COUNT',
        'STOCK_TRANSFORMATION', 'EXPENSE', 'DRAWER_RECONCILIATION', 'CASH_DROP', 'YEAR_END_CLOSE', 'MANUAL',
        'REVERSAL'))
);
CREATE INDEX ix_journal_entry_tenant_period ON finance.journal_entry (tenant_id, period);
CREATE INDEX ix_journal_entry_tenant_source ON finance.journal_entry (tenant_id, source_type, source_id);
CREATE INDEX ix_journal_entry_tenant_date ON finance.journal_entry (tenant_id, entry_date, id);

-- Lines: a positive debit or a positive credit. Dimensions are columns because reports and the period close query by
-- them (06 §3: jsonb never for data queried by).
CREATE TABLE finance.journal_line
(
    id          uuid           NOT NULL,
    tenant_id   uuid           NOT NULL,
    journal_id  uuid           NOT NULL,
    line_no     integer        NOT NULL,
    account_id  uuid           NOT NULL,
    debit       numeric(19, 4) NOT NULL,
    credit      numeric(19, 4) NOT NULL,
    party_type  text,
    party_id    uuid,
    variant_id  uuid,
    product_id  uuid,
    channel     text,
    location_id uuid,
    bill_id     uuid,
    CONSTRAINT pk_journal_line PRIMARY KEY (id),
    CONSTRAINT uk_journal_line_tenant_journal_no UNIQUE (tenant_id, journal_id, line_no),
    CONSTRAINT fk_journal_line_journal FOREIGN KEY (tenant_id, journal_id)
        REFERENCES finance.journal_entry (tenant_id, id),
    CONSTRAINT fk_journal_line_account FOREIGN KEY (tenant_id, account_id) REFERENCES finance.account (tenant_id, id),
    CONSTRAINT ck_journal_line_amounts CHECK (debit >= 0 AND credit >= 0 AND (debit > 0) <> (credit > 0)),
    CONSTRAINT ck_journal_line_party CHECK ((party_type IS NULL) = (party_id IS NULL)
        AND (party_type IS NULL OR party_type IN ('CUSTOMER', 'SUPPLIER')))
);
CREATE INDEX ix_journal_line_tenant_account ON finance.journal_line (tenant_id, account_id);
CREATE INDEX ix_journal_line_tenant_bill ON finance.journal_line (tenant_id, bill_id) WHERE bill_id IS NOT NULL;
CREATE INDEX ix_journal_line_tenant_party ON finance.journal_line (tenant_id, party_type, party_id)
    WHERE party_id IS NOT NULL;

-- FIN-05, FIN-06: a period's status; a missing row is OPEN. Postings take the row FOR SHARE, a close FOR UPDATE.
CREATE TABLE finance.fiscal_period
(
    tenant_id uuid        NOT NULL,
    period    char(7)     NOT NULL,
    status    text        NOT NULL,
    closed_at timestamptz,
    closed_by uuid,
    CONSTRAINT pk_fiscal_period PRIMARY KEY (tenant_id, period),
    CONSTRAINT ck_fiscal_period_period CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_fiscal_period_status CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    CONSTRAINT ck_fiscal_period_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- FIN-07: open items per party, signed (positive owed, negative a credit not yet applied).
CREATE TABLE finance.open_item
(
    id            uuid           NOT NULL,
    tenant_id     uuid           NOT NULL,
    party_type    text           NOT NULL,
    party_id      uuid           NOT NULL,
    document_type text           NOT NULL,
    document_id   uuid           NOT NULL,
    amount        numeric(19, 4) NOT NULL,
    settled       numeric(19, 4) NOT NULL,
    document_date date           NOT NULL,
    due_date      date           NOT NULL,
    status        text           NOT NULL,
    created_at    timestamptz    NOT NULL,
    updated_at    timestamptz    NOT NULL,
    CONSTRAINT pk_open_item PRIMARY KEY (id),
    CONSTRAINT uk_open_item_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_open_item_tenant_document UNIQUE (tenant_id, party_type, document_type, document_id),
    CONSTRAINT ck_open_item_party_type CHECK (party_type IN ('CUSTOMER', 'SUPPLIER')),
    CONSTRAINT ck_open_item_document_type CHECK (document_type IN (
        'BILL', 'COLLECTION', 'RETURN', 'VOID', 'REFUND', 'SUPPLIER_INVOICE', 'SUPPLIER_PAYMENT', 'EXPENSE',
        'SUPPLIER_RETURN')),
    CONSTRAINT ck_open_item_status CHECK (status IN ('OPEN', 'SETTLED')),
    CONSTRAINT ck_open_item_amount CHECK (amount <> 0),
    CONSTRAINT ck_open_item_settled CHECK (abs(settled) <= abs(amount) AND sign(settled) * sign(amount) >= 0),
    CONSTRAINT ck_open_item_status_settled CHECK ((status = 'SETTLED') = (settled = amount))
);
CREATE INDEX ix_open_item_tenant_party ON finance.open_item (tenant_id, party_type, party_id, status);
CREATE INDEX ix_open_item_tenant_due ON finance.open_item (tenant_id, status, due_date);

-- FIN-07: what each item took off another; never changed.
CREATE TABLE finance.open_item_settlement
(
    id              uuid           NOT NULL,
    tenant_id       uuid           NOT NULL,
    item_id         uuid           NOT NULL,
    against_item_id uuid           NOT NULL,
    amount          numeric(19, 4) NOT NULL,
    settled_at      timestamptz    NOT NULL,
    CONSTRAINT pk_open_item_settlement PRIMARY KEY (id),
    CONSTRAINT fk_open_item_settlement_item FOREIGN KEY (tenant_id, item_id) REFERENCES finance.open_item (tenant_id, id),
    CONSTRAINT fk_open_item_settlement_against FOREIGN KEY (tenant_id, against_item_id)
        REFERENCES finance.open_item (tenant_id, id),
    CONSTRAINT ck_open_item_settlement_amount CHECK (amount > 0)
);
CREATE INDEX ix_open_item_settlement_tenant_item ON finance.open_item_settlement (tenant_id, item_id);
CREATE INDEX ix_open_item_settlement_tenant_against ON finance.open_item_settlement (tenant_id, against_item_id);

-- Projection: Σ open per party, kept in the posting transaction and rebuildable (06 §6).
CREATE TABLE finance.party_balance
(
    tenant_id        uuid           NOT NULL,
    party_type       text           NOT NULL,
    party_id         uuid           NOT NULL,
    balance          numeric(19, 4) NOT NULL,
    oldest_open_date date,
    CONSTRAINT pk_party_balance PRIMARY KEY (tenant_id, party_type, party_id),
    CONSTRAINT ck_party_balance_party_type CHECK (party_type IN ('CUSTOMER', 'SUPPLIER'))
);

-- Replica of parties from party events: payment terms for due dates (ADR-032 §10).
CREATE TABLE finance.party_ref
(
    tenant_id          uuid    NOT NULL,
    party_id           uuid    NOT NULL,
    type               text    NOT NULL,
    payment_terms_days integer NOT NULL,
    status             text    NOT NULL,
    aggregate_version  bigint  NOT NULL,
    CONSTRAINT pk_party_ref PRIMARY KEY (tenant_id, party_id),
    CONSTRAINT ck_party_ref_type CHECK (type IN ('CUSTOMER', 'SUPPLIER', 'BOTH')),
    CONSTRAINT ck_party_ref_terms CHECK (payment_terms_days >= 0)
);

-- The line totals procurement posted for goods receipts: a return to the supplier leaves stock at current cost and the
-- difference to its line total goes to PPV (ADR-032 §5).
CREATE TABLE finance.receipt_line
(
    tenant_id  uuid           NOT NULL,
    line_id    uuid           NOT NULL,
    receipt_id uuid           NOT NULL,
    line_total numeric(19, 4) NOT NULL,
    CONSTRAINT pk_receipt_line PRIMARY KEY (tenant_id, line_id)
);

-- FIN-09: expense categories, each posting to an expense account.
CREATE TABLE finance.expense_category
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    account_id uuid        NOT NULL,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_expense_category PRIMARY KEY (id),
    CONSTRAINT uk_expense_category_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_expense_category_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_expense_category_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES finance.account (tenant_id, id),
    CONSTRAINT ck_expense_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- FIN-09: expenses; never changed (a wrong one is reversed through its journal).
CREATE TABLE finance.expense
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    category_id       uuid           NOT NULL,
    expense_date      date           NOT NULL,
    amount            numeric(19, 4) NOT NULL,
    currency          text           NOT NULL,
    paid_from         text           NOT NULL,
    drawer_session_id uuid,
    supplier_id       uuid,
    due_date          date,
    description       text           NOT NULL,
    attachment_keys   text[]         NOT NULL DEFAULT '{}',
    journal_id        uuid           NOT NULL,
    created_at        timestamptz    NOT NULL,
    created_by        uuid,
    CONSTRAINT pk_expense PRIMARY KEY (id),
    CONSTRAINT fk_expense_category FOREIGN KEY (tenant_id, category_id)
        REFERENCES finance.expense_category (tenant_id, id),
    CONSTRAINT fk_expense_journal FOREIGN KEY (tenant_id, journal_id) REFERENCES finance.journal_entry (tenant_id, id),
    CONSTRAINT ck_expense_amount CHECK (amount > 0),
    CONSTRAINT ck_expense_paid_from CHECK (paid_from IN ('CASH_DRAWER', 'BANK', 'PAYABLE')),
    CONSTRAINT ck_expense_drawer CHECK ((paid_from = 'CASH_DRAWER') = (drawer_session_id IS NOT NULL)),
    CONSTRAINT ck_expense_supplier CHECK (paid_from <> 'PAYABLE' OR supplier_id IS NOT NULL)
);
CREATE INDEX ix_expense_tenant_date ON finance.expense (tenant_id, expense_date, id);

-- FIN-11: supplier payments and the documents they were allocated to; never changed.
CREATE TABLE finance.supplier_payment
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    supplier_id       uuid           NOT NULL,
    amount            numeric(19, 4) NOT NULL,
    currency          text           NOT NULL,
    paid_from         text           NOT NULL,
    drawer_session_id uuid,
    reference         text,
    paid_at           timestamptz    NOT NULL,
    journal_id        uuid           NOT NULL,
    created_at        timestamptz    NOT NULL,
    created_by        uuid,
    CONSTRAINT pk_supplier_payment PRIMARY KEY (id),
    CONSTRAINT uk_supplier_payment_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_supplier_payment_journal FOREIGN KEY (tenant_id, journal_id)
        REFERENCES finance.journal_entry (tenant_id, id),
    CONSTRAINT ck_supplier_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_supplier_payment_paid_from CHECK (paid_from IN ('BANK', 'CASH_DRAWER')),
    CONSTRAINT ck_supplier_payment_drawer CHECK ((paid_from = 'CASH_DRAWER') = (drawer_session_id IS NOT NULL))
);
CREATE INDEX ix_supplier_payment_tenant_supplier ON finance.supplier_payment (tenant_id, supplier_id, id);

CREATE TABLE finance.supplier_payment_allocation
(
    tenant_id   uuid           NOT NULL,
    payment_id  uuid           NOT NULL,
    document_id uuid           NOT NULL,
    amount      numeric(19, 4) NOT NULL,
    CONSTRAINT pk_supplier_payment_allocation PRIMARY KEY (tenant_id, payment_id, document_id),
    CONSTRAINT fk_supplier_payment_allocation_payment FOREIGN KEY (tenant_id, payment_id)
        REFERENCES finance.supplier_payment (tenant_id, id),
    CONSTRAINT ck_supplier_payment_allocation_amount CHECK (amount > 0)
);

-- Cash taken from a drawer to the bank; never changed.
CREATE TABLE finance.cash_drop
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    drawer_session_id uuid           NOT NULL,
    register_id       uuid           NOT NULL,
    amount            numeric(19, 4) NOT NULL,
    currency          text           NOT NULL,
    reference         text,
    dropped_at        timestamptz    NOT NULL,
    journal_id        uuid           NOT NULL,
    created_at        timestamptz    NOT NULL,
    created_by        uuid,
    CONSTRAINT pk_cash_drop PRIMARY KEY (id),
    CONSTRAINT fk_cash_drop_journal FOREIGN KEY (tenant_id, journal_id) REFERENCES finance.journal_entry (tenant_id, id),
    CONSTRAINT ck_cash_drop_amount CHECK (amount > 0)
);
CREATE INDEX ix_cash_drop_tenant_session ON finance.cash_drop (tenant_id, drawer_session_id, id);

-- FIN-08: when each customer was last reported overdue (at most once a week).
CREATE TABLE finance.overdue_notice
(
    tenant_id        uuid NOT NULL,
    customer_id      uuid NOT NULL,
    last_notified_on date NOT NULL,
    CONSTRAINT pk_overdue_notice PRIMARY KEY (tenant_id, customer_id)
);

DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['number_sequence', 'account', 'journal_entry', 'journal_line', 'fiscal_period',
            'open_item', 'open_item_settlement', 'party_balance', 'party_ref', 'receipt_line', 'expense_category',
            'expense', 'supplier_payment', 'supplier_payment_allocation', 'cash_drop', 'overdue_notice']
            LOOP
                EXECUTE format('ALTER TABLE finance.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE finance.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON finance.%I USING (tenant_id = platform.current_tenant_id())'
                                   || ' WITH CHECK (tenant_id = platform.current_tenant_id())', t);
            END LOOP;
    END
$$;

GRANT SELECT, INSERT, UPDATE ON finance.number_sequence, finance.account, finance.fiscal_period, finance.open_item,
    finance.party_balance, finance.party_ref, finance.expense_category, finance.overdue_notice TO bmp_app;
GRANT SELECT, INSERT ON finance.journal_entry, finance.journal_line, finance.open_item_settlement, finance.receipt_line,
    finance.expense, finance.supplier_payment, finance.supplier_payment_allocation, finance.cash_drop TO bmp_app;
-- FIN-08: the daily overdue job finds the tenants to visit across tenants (ADR-032 §15).
GRANT SELECT ON finance.open_item TO bmp_platform_admin;
