-- Audit module schema (docs/reference/data-model.md, section audit; 05-logging-and-observability.md section 9).
-- audit_record is a ledger: bmp_app may only INSERT and SELECT. Corrections are new records, never updates.
GRANT USAGE ON SCHEMA audit TO bmp_app;

-- One hash chain per tenant: hash = SHA-256 over the record's fields and prev_hash; sequence numbers have no gaps.
CREATE TABLE audit.audit_record
(
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    sequence       bigint      NOT NULL,
    occurred_at    timestamptz NOT NULL,
    recorded_at    timestamptz NOT NULL,
    source         text        NOT NULL,
    action         text        NOT NULL,
    resource_type  text,
    resource_id    text,
    outcome        int,
    actor_id       uuid,
    device_id      uuid,
    platform_admin boolean     NOT NULL DEFAULT false,
    before_hash    text,
    after_hash     text,
    ip             text,
    user_agent     text,
    request_id     text,
    event_id       uuid,
    prev_hash      text        NOT NULL,
    hash           text        NOT NULL,
    CONSTRAINT pk_audit_record PRIMARY KEY (id),
    CONSTRAINT uk_audit_record_tenant_sequence UNIQUE (tenant_id, sequence),
    CONSTRAINT ck_audit_record_source CHECK (source IN ('API', 'EVENT', 'SYSTEM')),
    CONSTRAINT ck_audit_record_sequence CHECK (sequence > 0)
);
CREATE UNIQUE INDEX uk_audit_record_tenant_event ON audit.audit_record (tenant_id, event_id) WHERE event_id IS NOT NULL;
CREATE INDEX ix_audit_record_tenant_resource ON audit.audit_record (tenant_id, resource_type, resource_id, sequence);
CREATE INDEX ix_audit_record_tenant_occurred_at ON audit.audit_record (tenant_id, occurred_at);
ALTER TABLE audit.audit_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.audit_record
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON audit.audit_record TO bmp_app;
