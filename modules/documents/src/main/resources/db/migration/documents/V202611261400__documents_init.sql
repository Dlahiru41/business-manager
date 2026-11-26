-- Documents module schema (docs/reference/data-model.md, section documents; phase 03, DOC-05, DOC-06).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
GRANT USAGE ON SCHEMA documents TO bmp_app;

-- DOC-05: the print agent at a location, which drives its receipt printer.
CREATE TABLE documents.print_agent
(
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    location_id    uuid        NOT NULL,
    name           text        NOT NULL,
    printer_model  text,
    paper_width_mm integer     NOT NULL,
    status         text        NOT NULL,
    last_seen_at   timestamptz,
    created_at     timestamptz NOT NULL,
    created_by     uuid,
    updated_at     timestamptz NOT NULL,
    updated_by     uuid,
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_print_agent PRIMARY KEY (id),
    CONSTRAINT uk_print_agent_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_print_agent_paper CHECK (paper_width_mm IN (58, 80)),
    CONSTRAINT ck_print_agent_status CHECK (status IN ('ONLINE', 'OFFLINE', 'PAPER_OUT', 'ERROR'))
);
CREATE INDEX ix_print_agent_tenant_location ON documents.print_agent (tenant_id, location_id);

-- DOC-05: a receipt to print on an agent; handed out by long polling, its result reported back.
CREATE TABLE documents.print_job
(
    id               uuid        NOT NULL,
    tenant_id        uuid        NOT NULL,
    agent_id         uuid        NOT NULL,
    document_type    text        NOT NULL,
    source_id        uuid,
    receipt          jsonb       NOT NULL,
    template_version bigint,
    status           text        NOT NULL,
    attempts         integer     NOT NULL DEFAULT 0,
    error            text,
    sent_at          timestamptz,
    created_at       timestamptz NOT NULL,
    created_by       uuid,
    updated_at       timestamptz NOT NULL,
    updated_by       uuid,
    completed_at     timestamptz,
    version          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_print_job PRIMARY KEY (id),
    CONSTRAINT fk_print_job_agent FOREIGN KEY (tenant_id, agent_id) REFERENCES documents.print_agent (tenant_id, id),
    CONSTRAINT ck_print_job_status CHECK (status IN ('QUEUED', 'SENT', 'PRINTED', 'FAILED'))
);
CREATE INDEX ix_print_job_tenant_agent_status ON documents.print_job (tenant_id, agent_id, status, created_at);

DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['print_agent', 'print_job']
            LOOP
                EXECUTE format('ALTER TABLE documents.%I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE documents.%I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format('CREATE POLICY tenant_isolation ON documents.%I USING (tenant_id = platform.current_tenant_id())'
                                   || ' WITH CHECK (tenant_id = platform.current_tenant_id())', t);
            END LOOP;
    END
$$;

GRANT SELECT, INSERT, UPDATE ON documents.print_agent, documents.print_job TO bmp_app;
