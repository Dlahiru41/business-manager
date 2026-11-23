-- Transformation runs and stock counts (phase 02, INV-09, INV-10, INV-12).

-- Runs (INV-09): never changed; their movements are in the ledger with source_type TRANSFORMATION.
CREATE TABLE inventory.transformation_run
(
    tenant_id          uuid           NOT NULL,
    id                 uuid           NOT NULL,
    definition_id      uuid           NOT NULL,
    def_version        integer        NOT NULL,
    kind               text           NOT NULL,
    location_id        uuid           NOT NULL,
    runs               numeric(20, 6) NOT NULL,
    inputs             jsonb          NOT NULL,
    outputs            jsonb          NOT NULL,
    yield_variance_qty numeric(20, 6) NOT NULL,
    total_cost         numeric(19, 4) NOT NULL,
    currency           text           NOT NULL,
    note               text,
    occurred_at        timestamptz    NOT NULL,
    actor_id           uuid,
    negative_stock     boolean        NOT NULL DEFAULT false,
    CONSTRAINT pk_transformation_run PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_transformation_run_runs CHECK (runs > 0)
);
CREATE INDEX ix_transformation_run_tenant_definition ON inventory.transformation_run (tenant_id, definition_id, id);
ALTER TABLE inventory.transformation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transformation_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.transformation_run
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON inventory.transformation_run TO bmp_app;

-- Stock counts (INV-12): posted counts never change (checked by the aggregate).
CREATE TABLE inventory.stock_count
(
    tenant_id    uuid        NOT NULL,
    id           uuid        NOT NULL,
    location_id  uuid        NOT NULL,
    status       text        NOT NULL,
    note         text,
    variant_ids  uuid[]      NOT NULL DEFAULT '{}',
    started_at   timestamptz NOT NULL,
    submitted_at timestamptz,
    posted_at    timestamptz,
    posted_by    uuid,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_stock_count PRIMARY KEY (id),
    CONSTRAINT uk_stock_count_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_stock_count_status CHECK (status IN ('OPEN', 'SUBMITTED', 'POSTED', 'CANCELLED'))
);
CREATE INDEX ix_stock_count_tenant_location ON inventory.stock_count (tenant_id, location_id, id);
ALTER TABLE inventory.stock_count ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_count FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_count
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.stock_count TO bmp_app;

CREATE TABLE inventory.stock_count_line
(
    tenant_id       uuid           NOT NULL DEFAULT platform.current_tenant_id(),
    id              uuid           NOT NULL,
    count_id        uuid           NOT NULL,
    variant_id      uuid           NOT NULL,
    stock_state     text           NOT NULL,
    batch_id        uuid           NOT NULL,
    system_qty      numeric(20, 6) NOT NULL,
    counted_qty     numeric(20, 6) NOT NULL,
    variance_qty    numeric(20, 6),
    variance_cost   numeric(19, 4),
    variance_reason text,
    snapshot_at     timestamptz    NOT NULL,
    CONSTRAINT pk_stock_count_line PRIMARY KEY (id),
    CONSTRAINT fk_stock_count_line_count FOREIGN KEY (tenant_id, count_id)
        REFERENCES inventory.stock_count (tenant_id, id),
    CONSTRAINT uk_stock_count_line_item UNIQUE (tenant_id, count_id, variant_id, stock_state, batch_id),
    CONSTRAINT ck_stock_count_line_counted CHECK (counted_qty >= 0)
);
ALTER TABLE inventory.stock_count_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_count_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_count_line
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.stock_count_line TO bmp_app;
