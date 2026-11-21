-- Test-only module used to prove the persistence infrastructure (T00-04).
GRANT USAGE ON SCHEMA sample TO bmp_app;

CREATE TABLE sample.widget
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    name       text        NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    CONSTRAINT pk_widget PRIMARY KEY (id),
    CONSTRAINT uk_widget_tenant_name UNIQUE (tenant_id, name)
);
ALTER TABLE sample.widget ENABLE ROW LEVEL SECURITY;
ALTER TABLE sample.widget FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sample.widget
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON sample.widget TO bmp_app;

CREATE TABLE sample.number_sequence
(
    tenant_id  uuid   NOT NULL,
    scope      text   NOT NULL,
    prefix     text   NOT NULL,
    next_value bigint NOT NULL,
    CONSTRAINT pk_number_sequence PRIMARY KEY (tenant_id, scope)
);
ALTER TABLE sample.number_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE sample.number_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sample.number_sequence
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON sample.number_sequence TO bmp_app;

-- A ledger: insert/select only.
CREATE TABLE sample.entry
(
    id          uuid          NOT NULL,
    tenant_id   uuid          NOT NULL,
    amount      numeric(19, 4) NOT NULL,
    occurred_at timestamptz   NOT NULL,
    CONSTRAINT pk_entry PRIMARY KEY (id)
);
CREATE INDEX ix_entry_tenant_occurred_at ON sample.entry (tenant_id, occurred_at);
ALTER TABLE sample.entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE sample.entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sample.entry
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON sample.entry TO bmp_app;
