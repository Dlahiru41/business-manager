-- Stock ledger and balances (docs/reference/data-model.md, section inventory; phase 02, INV-01..INV-04, INV-13,
-- INV-14). Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.

-- The ledger (INV-01, INV-02): insert-only, in base units, partitioned by month of occurred_at (06 §6). Partitions
-- for 2026-2028 are created here, later months by a later migration; anything outside lands in the default partition.
-- Every partition has row-level security of its own; bmp_app reaches them only through the parent.
CREATE TABLE inventory.stock_movement
(
    id             uuid           NOT NULL,
    tenant_id      uuid           NOT NULL,
    variant_id     uuid           NOT NULL,
    location_id    uuid           NOT NULL,
    stock_state    text           NOT NULL,
    batch_id       uuid           NOT NULL,
    qty_base       numeric(20, 6) NOT NULL,
    unit_cost      numeric(19, 6) NOT NULL,
    cost_amount    numeric(19, 4) NOT NULL,
    currency       text           NOT NULL,
    type           text           NOT NULL,
    reason_code    text,
    source_type    text           NOT NULL,
    source_id      uuid,
    source_line_id uuid,
    flags          text[]         NOT NULL DEFAULT '{}',
    occurred_at    timestamptz    NOT NULL,
    recorded_at    timestamptz    NOT NULL,
    actor_id       uuid,
    device_id      uuid,
    CONSTRAINT pk_stock_movement PRIMARY KEY (tenant_id, id, occurred_at),
    CONSTRAINT ck_stock_movement_type CHECK (type IN ('OPENING', 'RECEIPT', 'SALE', 'SALE_RETURN', 'SALE_VOID',
        'TRANSFORM_IN', 'TRANSFORM_OUT', 'WASTAGE', 'COUNT_ADJ', 'CORRECTION', 'TRANSFER_IN', 'TRANSFER_OUT')),
    CONSTRAINT ck_stock_movement_qty CHECK ((type = 'CORRECTION') = (qty_base = 0)),
    CONSTRAINT ck_stock_movement_cost_sign CHECK (type = 'CORRECTION' OR cost_amount = 0
        OR sign(cost_amount) = sign(qty_base)),
    CONSTRAINT ck_stock_movement_unit_cost CHECK (unit_cost >= 0),
    CONSTRAINT ck_stock_movement_currency CHECK (currency ~ '^[A-Z]{3}$')
) PARTITION BY RANGE (occurred_at);
CREATE INDEX ix_stock_movement_tenant_item ON inventory.stock_movement (tenant_id, variant_id, location_id, occurred_at);
CREATE INDEX ix_stock_movement_tenant_source ON inventory.stock_movement (tenant_id, source_type, source_id);
CREATE INDEX ix_stock_movement_tenant_time ON inventory.stock_movement (tenant_id, occurred_at, id);
ALTER TABLE inventory.stock_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_movement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_movement
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON inventory.stock_movement TO bmp_app;

DO $$
DECLARE
    month date := date '2026-01-01';
    part  text;
BEGIN
    WHILE month < date '2029-01-01'
        LOOP
            part := 'stock_movement_' || to_char(month, 'YYYYMM');
            EXECUTE format('CREATE TABLE inventory.%I PARTITION OF inventory.stock_movement FOR VALUES FROM (%L) TO (%L)',
                           part, month::timestamptz, (month + interval '1 month')::timestamptz);
            EXECUTE format('ALTER TABLE inventory.%I ENABLE ROW LEVEL SECURITY', part);
            EXECUTE format('ALTER TABLE inventory.%I FORCE ROW LEVEL SECURITY', part);
            EXECUTE format('CREATE POLICY tenant_isolation ON inventory.%I USING (tenant_id = platform.current_tenant_id())'
                               || ' WITH CHECK (tenant_id = platform.current_tenant_id())', part);
            month := month + interval '1 month';
        END LOOP;
END
$$;
CREATE TABLE inventory.stock_movement_default PARTITION OF inventory.stock_movement DEFAULT;
ALTER TABLE inventory.stock_movement_default ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_movement_default FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_movement_default
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());

-- Balances: the projection of the ledger per variant, location, state and batch, updated in the posting transaction
-- under a row lock (06 §6) and rebuildable from the ledger (INV-16). stock_value = Σ cost_amount of its movements.
CREATE TABLE inventory.stock_balance
(
    tenant_id        uuid           NOT NULL,
    variant_id       uuid           NOT NULL,
    location_id      uuid           NOT NULL,
    stock_state      text           NOT NULL,
    batch_id         uuid           NOT NULL,
    on_hand          numeric(20, 6) NOT NULL DEFAULT 0,
    reserved         numeric(20, 6) NOT NULL DEFAULT 0,
    avg_unit_cost    numeric(19, 6) NOT NULL DEFAULT 0,
    stock_value      numeric(19, 4) NOT NULL DEFAULT 0,
    last_movement_id uuid,
    last_movement_at timestamptz,
    version          bigint         NOT NULL DEFAULT 0,
    updated_at       timestamptz    NOT NULL,
    CONSTRAINT pk_stock_balance PRIMARY KEY (tenant_id, variant_id, location_id, stock_state, batch_id),
    CONSTRAINT ck_stock_balance_avg CHECK (avg_unit_cost >= 0)
);
CREATE INDEX ix_stock_balance_tenant_location ON inventory.stock_balance (tenant_id, location_id, variant_id);
ALTER TABLE inventory.stock_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_balance
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.stock_balance TO bmp_app;

-- FIFO cost layers (INV-04): what is left of each receipt and its value. Exhausted layers stay with qty 0.
CREATE TABLE inventory.cost_layer
(
    tenant_id       uuid           NOT NULL,
    id              uuid           NOT NULL,
    variant_id      uuid           NOT NULL,
    location_id     uuid           NOT NULL,
    stock_state     text           NOT NULL,
    batch_id        uuid           NOT NULL,
    qty_remaining   numeric(20, 6) NOT NULL,
    value_remaining numeric(19, 4) NOT NULL,
    unit_cost       numeric(19, 6) NOT NULL,
    received_at     timestamptz    NOT NULL,
    CONSTRAINT pk_cost_layer PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_cost_layer_qty CHECK (qty_remaining >= 0)
);
CREATE INDEX ix_cost_layer_tenant_balance ON inventory.cost_layer
    (tenant_id, variant_id, location_id, stock_state, batch_id, received_at, id) WHERE qty_remaining > 0;
ALTER TABLE inventory.cost_layer ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.cost_layer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.cost_layer
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.cost_layer TO bmp_app;

-- Batches of variants that track them (INV-14).
CREATE TABLE inventory.batch
(
    tenant_id   uuid        NOT NULL,
    id          uuid        NOT NULL,
    variant_id  uuid        NOT NULL,
    code        text        NOT NULL,
    received_at timestamptz NOT NULL,
    expires_on  date,
    supplier_id uuid,
    CONSTRAINT pk_batch PRIMARY KEY (tenant_id, id),
    CONSTRAINT uk_batch_tenant_variant_code UNIQUE (tenant_id, variant_id, code)
);
ALTER TABLE inventory.batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.batch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.batch
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON inventory.batch TO bmp_app;

-- Reorder levels per variant and location (INV-13); low_flag makes one alert per crossing.
CREATE TABLE inventory.reorder_policy
(
    tenant_id             uuid           NOT NULL,
    variant_id            uuid           NOT NULL,
    location_id           uuid           NOT NULL,
    reorder_level         numeric(20, 6) NOT NULL,
    reorder_qty           numeric(20, 6) NOT NULL,
    preferred_supplier_id uuid,
    low_flag              boolean        NOT NULL DEFAULT false,
    version               bigint         NOT NULL DEFAULT 0,
    created_at            timestamptz    NOT NULL,
    updated_at            timestamptz    NOT NULL,
    CONSTRAINT pk_reorder_policy PRIMARY KEY (tenant_id, variant_id, location_id),
    CONSTRAINT ck_reorder_policy_level CHECK (reorder_level >= 0 AND reorder_qty >= 0)
);
ALTER TABLE inventory.reorder_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.reorder_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.reorder_policy
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.reorder_policy TO bmp_app;
