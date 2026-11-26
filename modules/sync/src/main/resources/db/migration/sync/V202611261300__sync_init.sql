-- Sync module schema (docs/reference/data-model.md, section sync; phase 03, SYN-01..SYN-08).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
GRANT USAGE ON SCHEMA sync TO bmp_app;

-- SYN-05: every uploaded record by its id; a replay returns what happened the first time.
CREATE TABLE sync.upload_record
(
    tenant_id   uuid        NOT NULL,
    record_type text        NOT NULL,
    record_id   uuid        NOT NULL,
    device_id   uuid        NOT NULL,
    status      text        NOT NULL,
    problem     jsonb,
    received_at timestamptz NOT NULL,
    CONSTRAINT pk_upload_record PRIMARY KEY (tenant_id, record_type, record_id),
    CONSTRAINT ck_upload_record_status CHECK (status IN ('ACCEPTED', 'REJECTED'))
);
CREATE INDEX ix_upload_record_tenant_id ON sync.upload_record (tenant_id, record_id);

-- SYN-01, SYN-07: what sync knows of a device: its last bootstrap and upload, clock skew and flags.
CREATE TABLE sync.device_state
(
    tenant_id          uuid        NOT NULL,
    device_id          uuid        NOT NULL,
    register_id        uuid        NOT NULL,
    last_bootstrap_at  timestamptz,
    last_upload_at     timestamptz,
    clock_skew_seconds bigint,
    flags              text[]      NOT NULL DEFAULT '{}',
    CONSTRAINT pk_device_state PRIMARY KEY (tenant_id, device_id)
);

-- SYN-06: bill numbers reported missing, so each gap is announced once.
CREATE TABLE sync.reported_gap
(
    tenant_id   uuid        NOT NULL,
    register_id uuid        NOT NULL,
    sequence    bigint      NOT NULL,
    reported_at timestamptz NOT NULL,
    CONSTRAINT pk_reported_gap PRIMARY KEY (tenant_id, register_id, sequence)
);

DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['upload_record', 'device_state', 'reported_gap']
            LOOP
                EXECUTE format('ALTER TABLE sync.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE sync.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON sync.%I USING (tenant_id = platform.current_tenant_id())'
                                   || ' WITH CHECK (tenant_id = platform.current_tenant_id())', t);
            END LOOP;
    END
$$;

GRANT SELECT, INSERT ON sync.upload_record, sync.reported_gap TO bmp_app;
GRANT SELECT, INSERT, UPDATE ON sync.device_state TO bmp_app;
