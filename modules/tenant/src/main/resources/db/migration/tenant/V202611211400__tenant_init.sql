-- Tenant module schema (docs/reference/data-model.md, section tenant). Runs as bmp_migrator; bmp_app gets DML only.
GRANT USAGE ON SCHEMA tenant TO bmp_app, bmp_platform_admin;

-- The tenant itself has no tenant_id column: the row IS the tenant, so its policy compares the id.
CREATE TABLE tenant.tenant
(
    id                       uuid        NOT NULL,
    name                     text        NOT NULL,
    legal_name               text,
    business_type            text        NOT NULL,
    currency                 char(3)     NOT NULL,
    time_zone                text        NOT NULL,
    business_day_cutoff_hour int         NOT NULL,
    status                   text        NOT NULL,
    plan                     text,
    version                  bigint      NOT NULL DEFAULT 0,
    created_at               timestamptz NOT NULL,
    created_by               uuid,
    updated_at               timestamptz NOT NULL,
    updated_by               uuid,
    CONSTRAINT pk_tenant PRIMARY KEY (id),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_tenant_cutoff_hour CHECK (business_day_cutoff_hour BETWEEN 0 AND 23)
);
ALTER TABLE tenant.tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant.tenant
    USING (id = platform.current_tenant_id())
    WITH CHECK (id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON tenant.tenant TO bmp_app;
GRANT SELECT ON tenant.tenant TO bmp_platform_admin;

CREATE TABLE tenant.location
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    name       text        NOT NULL,
    type       text        NOT NULL,
    address    jsonb,
    status     text        NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    CONSTRAINT pk_location PRIMARY KEY (id),
    CONSTRAINT ck_location_type CHECK (type IN ('SHOP', 'STORE', 'WAREHOUSE')),
    CONSTRAINT ck_location_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uk_location_tenant_name ON tenant.location (tenant_id, lower(name));
CREATE INDEX ix_location_tenant_created_at ON tenant.location (tenant_id, created_at, id);
ALTER TABLE tenant.location ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.location FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant.location
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON tenant.location TO bmp_app;

-- Business settings. Only values that differ from the schema defaults are stored; the group version is the highest
-- version of its rows (TEN-03, versioned).
CREATE TABLE tenant.setting
(
    tenant_id  uuid        NOT NULL,
    group_name text        NOT NULL,
    key        text        NOT NULL,
    value      jsonb       NOT NULL,
    version    bigint      NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    CONSTRAINT pk_setting PRIMARY KEY (tenant_id, group_name, key)
);
ALTER TABLE tenant.setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.setting FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant.setting
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON tenant.setting TO bmp_app;

-- Every settings change, append-only (TEN-03, versioned and audited).
CREATE TABLE tenant.setting_history
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    group_name text        NOT NULL,
    key        text        NOT NULL,
    old_value  jsonb,
    new_value  jsonb       NOT NULL,
    version    bigint      NOT NULL,
    changed_at timestamptz NOT NULL,
    changed_by uuid,
    CONSTRAINT pk_setting_history PRIMARY KEY (id)
);
CREATE INDEX ix_setting_history_tenant_group_changed_at ON tenant.setting_history (tenant_id, group_name, changed_at);
ALTER TABLE tenant.setting_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.setting_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant.setting_history
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON tenant.setting_history TO bmp_app;

CREATE TABLE tenant.receipt_template
(
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    name           text        NOT NULL,
    paper_width_mm int         NOT NULL,
    header         jsonb       NOT NULL,
    footer         jsonb       NOT NULL,
    fields         jsonb       NOT NULL,
    language       text        NOT NULL,
    is_default     boolean     NOT NULL DEFAULT false,
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    CONSTRAINT pk_receipt_template PRIMARY KEY (id),
    CONSTRAINT ck_receipt_template_paper_width CHECK (paper_width_mm IN (58, 80)),
    CONSTRAINT ck_receipt_template_language CHECK (language IN ('en', 'si', 'ta'))
);
CREATE UNIQUE INDEX uk_receipt_template_tenant_name ON tenant.receipt_template (tenant_id, lower(name));
CREATE UNIQUE INDEX uk_receipt_template_tenant_default ON tenant.receipt_template (tenant_id) WHERE is_default;
CREATE INDEX ix_receipt_template_tenant_created_at ON tenant.receipt_template (tenant_id, created_at, id);
ALTER TABLE tenant.receipt_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.receipt_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant.receipt_template
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON tenant.receipt_template TO bmp_app;
