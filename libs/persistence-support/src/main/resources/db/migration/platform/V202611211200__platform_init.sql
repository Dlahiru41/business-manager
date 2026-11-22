-- Platform infrastructure shared by every module (06-persistence-and-data.md §8).
-- Runs as bmp_migrator (schema owner). bmp_app gets DML only, so row-level security applies to it.
-- bmp_platform_admin has BYPASSRLS and only the grants platform jobs need.

GRANT USAGE ON SCHEMA platform TO bmp_app, bmp_platform_admin;

-- ---------------------------------------------------------------------------------------------------------------
-- Tenant isolation: every RLS policy compares tenant_id with this function. It fails closed (SQLSTATE BM001,
-- mapped to TENANT_CONTEXT_MISSING) instead of returning no rows when no tenant is set for the transaction.
-- ---------------------------------------------------------------------------------------------------------------
CREATE FUNCTION platform.current_tenant_id() RETURNS uuid
    LANGUAGE plpgsql
    STABLE
AS
$$
DECLARE
    v_tenant text := current_setting('app.tenant_id', true);
BEGIN
    IF v_tenant IS NULL OR v_tenant = '' THEN
        RAISE EXCEPTION 'TENANT_CONTEXT_MISSING: tenant-owned data accessed without a tenant'
            USING ERRCODE = 'BM001';
    END IF;
    RETURN v_tenant::uuid;
END
$$;

REVOKE ALL ON FUNCTION platform.current_tenant_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.current_tenant_id() TO bmp_app, bmp_platform_admin;

-- ---------------------------------------------------------------------------------------------------------------
-- Document numbers (ADR-012): each module owns <schema>.number_sequence; this function allocates the next number in
-- the caller's transaction. The table is passed as regclass, so the dynamic SQL cannot be injected. Invoker rights:
-- RLS on the module table still applies.
-- ---------------------------------------------------------------------------------------------------------------
CREATE FUNCTION platform.next_number(p_table regclass, p_tenant uuid, p_scope text) RETURNS bigint
    LANGUAGE plpgsql
    VOLATILE
AS
$$
DECLARE
    v_number bigint;
BEGIN
    EXECUTE format('UPDATE %s SET next_value = next_value + 1 WHERE tenant_id = $1 AND scope = $2 '
                       || 'RETURNING next_value - 1', p_table)
        INTO v_number USING p_tenant, p_scope;
    IF v_number IS NULL THEN
        EXECUTE format('INSERT INTO %s (tenant_id, scope, prefix, next_value) VALUES ($1, $2, '''', 1) '
                           || 'ON CONFLICT (tenant_id, scope) DO NOTHING', p_table)
            USING p_tenant, p_scope;
        EXECUTE format('UPDATE %s SET next_value = next_value + 1 WHERE tenant_id = $1 AND scope = $2 '
                           || 'RETURNING next_value - 1', p_table)
            INTO v_number USING p_tenant, p_scope;
    END IF;
    RETURN v_number;
END
$$;

REVOKE ALL ON FUNCTION platform.next_number(regclass, uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform.next_number(regclass, uuid, text) TO bmp_app;

-- ---------------------------------------------------------------------------------------------------------------
-- Idempotency records (03-api-standards.md §5). Keys are scoped per tenant; platform-admin requests without a tenant
-- use the nil UUID scope. response_body is text (not jsonb) so an unexpected non-JSON body can never break storing.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE platform.idempotency_record
(
    tenant_id        uuid        NOT NULL,
    idem_key         uuid        NOT NULL,
    request_hash     text        NOT NULL,
    status           text        NOT NULL,
    http_status      int,
    response_body    text,
    response_headers jsonb,
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (tenant_id, idem_key),
    CONSTRAINT ck_idempotency_record_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);
CREATE INDEX ix_idempotency_record_expires_at ON platform.idempotency_record (expires_at);

ALTER TABLE platform.idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.idempotency_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform.idempotency_record
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.idempotency_record TO bmp_app;
GRANT SELECT, DELETE ON platform.idempotency_record TO bmp_platform_admin;

-- ---------------------------------------------------------------------------------------------------------------
-- Inbox: (consumer, event) pairs already processed, for exactly-once listener effects (07-events-and-messaging.md §4).
-- Event ids are UUIDv7 and globally unique, so no tenant column is needed.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE platform.inbox
(
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL,
    CONSTRAINT pk_inbox PRIMARY KEY (consumer, event_id)
);
GRANT SELECT, INSERT ON platform.inbox TO bmp_app;
GRANT SELECT, DELETE ON platform.inbox TO bmp_platform_admin;

-- ---------------------------------------------------------------------------------------------------------------
-- Events a listener could not accept (non-retryable), parked with the problem for inspection and replay.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE platform.failed_event
(
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    consumer        text        NOT NULL,
    event_id        uuid        NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    problem         jsonb       NOT NULL,
    attempts        int         NOT NULL,
    first_failed_at timestamptz NOT NULL,
    last_failed_at  timestamptz NOT NULL,
    status          text        NOT NULL,
    CONSTRAINT pk_failed_event PRIMARY KEY (id),
    CONSTRAINT uk_failed_event_consumer_event UNIQUE (consumer, event_id),
    CONSTRAINT ck_failed_event_status CHECK (status IN ('PARKED', 'REPLAYED', 'DISCARDED'))
);
CREATE INDEX ix_failed_event_tenant_status ON platform.failed_event (tenant_id, status);

ALTER TABLE platform.failed_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.failed_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform.failed_event
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON platform.failed_event TO bmp_app;
GRANT SELECT, UPDATE ON platform.failed_event TO bmp_platform_admin;

-- ---------------------------------------------------------------------------------------------------------------
-- Long-running operations (202 Accepted, 03-api-standards.md §11).
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE platform.operation
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    type       text        NOT NULL,
    status     text        NOT NULL,
    progress   int         NOT NULL DEFAULT 0,
    result_ref text,
    problem    jsonb,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_operation PRIMARY KEY (id),
    CONSTRAINT ck_operation_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_operation_progress CHECK (progress BETWEEN 0 AND 100)
);
CREATE INDEX ix_operation_tenant_created_at ON platform.operation (tenant_id, created_at);

ALTER TABLE platform.operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform.operation
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON platform.operation TO bmp_app;

-- ---------------------------------------------------------------------------------------------------------------
-- ShedLock: scheduled jobs run once across instances.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE platform.shedlock
(
    name       varchar(64)  NOT NULL,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
GRANT SELECT, INSERT, UPDATE ON platform.shedlock TO bmp_app;

-- ---------------------------------------------------------------------------------------------------------------
-- Spring Modulith 2.2 JDBC event publication registry (schema v2 for PostgreSQL, copied from
-- spring-modulith-events-jdbc 2.2.0: org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql).
-- This is the transactional outbox. Not tenant-scoped: listeners restore the tenant from the event itself.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE platform.event_publication
(
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON platform.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON platform.event_publication (completion_date);
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.event_publication TO bmp_app;
GRANT SELECT ON platform.event_publication TO bmp_platform_admin;
