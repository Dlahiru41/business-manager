-- Inventory replicas (docs/reference/data-model.md, section inventory; phase 02, T02-03). Fed by catalog and tenant
-- events; every upsert keeps the newest aggregate version, and a deactivation that arrives first leaves a tombstone.
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
GRANT USAGE ON SCHEMA inventory TO bmp_app;

-- Variants as stock items: units with their size in base units and stock state (INV-05), tracking flags (INV-14).
CREATE TABLE inventory.stock_item
(
    tenant_id         uuid        NOT NULL,
    variant_id        uuid        NOT NULL,
    product_id        uuid        NOT NULL,
    sku               text        NOT NULL,
    name              text        NOT NULL,
    base_unit         text        NOT NULL,
    stock_states      text[]      NOT NULL,
    sell_mode         text        NOT NULL,
    packaging_units   jsonb       NOT NULL DEFAULT '[]',
    track_batches     boolean     NOT NULL,
    track_expiry      boolean     NOT NULL,
    decimal_qty       boolean     NOT NULL,
    status            text        NOT NULL,
    aggregate_version bigint      NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT pk_stock_item PRIMARY KEY (tenant_id, variant_id),
    CONSTRAINT ck_stock_item_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_stock_item_sell_mode CHECK (sell_mode IN ('PREPACKED', 'BREAK_ON_SALE')),
    CONSTRAINT ck_stock_item_units CHECK (jsonb_typeof(packaging_units) = 'array')
);
CREATE INDEX ix_stock_item_tenant_product ON inventory.stock_item (tenant_id, product_id);
ALTER TABLE inventory.stock_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_item
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.stock_item TO bmp_app;

-- Products, for valuation by product and category.
CREATE TABLE inventory.stock_product
(
    tenant_id         uuid   NOT NULL,
    product_id        uuid   NOT NULL,
    name              text   NOT NULL,
    category_id       uuid,
    aggregate_version bigint NOT NULL,
    CONSTRAINT pk_stock_product PRIMARY KEY (tenant_id, product_id)
);
ALTER TABLE inventory.stock_product ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_product FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_product
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.stock_product TO bmp_app;

-- Locations of the tenant.
CREATE TABLE inventory.location_ref
(
    tenant_id   uuid NOT NULL,
    location_id uuid NOT NULL,
    name        text NOT NULL,
    type        text NOT NULL,
    status      text NOT NULL,
    CONSTRAINT pk_location_ref PRIMARY KEY (tenant_id, location_id),
    CONSTRAINT ck_location_ref_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
ALTER TABLE inventory.location_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.location_ref FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.location_ref
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.location_ref TO bmp_app;

-- Published transformation definitions, one row per frozen version (CAT-10); retired versions stay for history.
CREATE TABLE inventory.transformation_def_ref
(
    tenant_id          uuid          NOT NULL,
    definition_id      uuid          NOT NULL,
    def_version        integer       NOT NULL,
    code               text          NOT NULL,
    name               text          NOT NULL,
    kind               text          NOT NULL,
    expected_yield_pct numeric(9, 4),
    lines              jsonb         NOT NULL,
    status             text          NOT NULL,
    CONSTRAINT pk_transformation_def_ref PRIMARY KEY (tenant_id, definition_id, def_version),
    CONSTRAINT ck_transformation_def_ref_status CHECK (status IN ('PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_transformation_def_ref_lines CHECK (jsonb_typeof(lines) = 'array')
);
ALTER TABLE inventory.transformation_def_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transformation_def_ref FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.transformation_def_ref
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON inventory.transformation_def_ref TO bmp_app;
