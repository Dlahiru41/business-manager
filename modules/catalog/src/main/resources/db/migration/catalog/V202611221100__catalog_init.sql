-- Catalog module schema (docs/reference/data-model.md, section catalog; phase 01, CAT-01..CAT-14).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
GRANT USAGE ON SCHEMA catalog TO bmp_app;

-- Units of measure (CAT-01). Platform standard units have no tenant and are readable by every tenant; tenants add
-- their own. This is the only catalog table whose policy lets rows without a tenant through.
CREATE TABLE catalog.unit_of_measure
(
    id           uuid           NOT NULL,
    tenant_id    uuid,
    code         text           NOT NULL,
    name         text           NOT NULL,
    dimension    text           NOT NULL,
    to_si_factor numeric(20, 10),
    created_at   timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT pk_unit_of_measure PRIMARY KEY (id),
    CONSTRAINT ck_unit_of_measure_code CHECK (code ~ '^[A-Za-z][A-Za-z0-9_.-]{0,31}$'),
    CONSTRAINT ck_unit_of_measure_dimension CHECK (dimension IN ('COUNT', 'MASS', 'VOLUME', 'LENGTH', 'AREA', 'TIME')),
    CONSTRAINT ck_unit_of_measure_factor CHECK (to_si_factor IS NULL OR to_si_factor > 0)
);
CREATE UNIQUE INDEX uk_unit_of_measure_tenant_code ON catalog.unit_of_measure (tenant_id, lower(code))
    WHERE tenant_id IS NOT NULL;
CREATE UNIQUE INDEX uk_unit_of_measure_standard_code ON catalog.unit_of_measure (lower(code))
    WHERE tenant_id IS NULL;
-- Standard units with their factor to the dimension's reference unit (kg, l, m, piece). A pack has no fixed size, so
-- it converts only through packaging units and product conversions (CAT-03). Seeded before row-level security is
-- forced: the forced policy applies to the owner too, and a migration runs without a tenant.
INSERT INTO catalog.unit_of_measure (id, tenant_id, code, name, dimension, to_si_factor)
VALUES ('0193a000-0000-7000-8000-000000000001', NULL, 'piece', 'Piece', 'COUNT', 1),
       ('0193a000-0000-7000-8000-000000000002', NULL, 'g', 'Gram', 'MASS', 0.001),
       ('0193a000-0000-7000-8000-000000000003', NULL, 'kg', 'Kilogram', 'MASS', 1),
       ('0193a000-0000-7000-8000-000000000004', NULL, 'ml', 'Millilitre', 'VOLUME', 0.001),
       ('0193a000-0000-7000-8000-000000000005', NULL, 'l', 'Litre', 'VOLUME', 1),
       ('0193a000-0000-7000-8000-000000000006', NULL, 'm', 'Metre', 'LENGTH', 1),
       ('0193a000-0000-7000-8000-000000000007', NULL, 'cm', 'Centimetre', 'LENGTH', 0.01),
       ('0193a000-0000-7000-8000-000000000008', NULL, 'pack', 'Pack', 'COUNT', NULL),
       ('0193a000-0000-7000-8000-000000000009', NULL, 'dozen', 'Dozen', 'COUNT', 12);
ALTER TABLE catalog.unit_of_measure ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.unit_of_measure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.unit_of_measure
    USING (tenant_id IS NULL OR tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
-- Units are immutable once created.
GRANT SELECT, INSERT ON catalog.unit_of_measure TO bmp_app;

-- The tenant's catalog version: every catalog change takes the next one (row lock), so till deltas never miss a
-- change committed out of order (CAT-13, catalog snapshot).
CREATE TABLE catalog.catalog_revision
(
    tenant_id uuid   NOT NULL,
    revision  bigint NOT NULL,
    CONSTRAINT pk_catalog_revision PRIMARY KEY (tenant_id),
    CONSTRAINT ck_catalog_revision_positive CHECK (revision >= 0)
);
ALTER TABLE catalog.catalog_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.catalog_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.catalog_revision
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.catalog_revision TO bmp_app;

-- Categories: a tree with a materialized path of ids (root/child/self), so subtrees are prefix queries.
CREATE TABLE catalog.category
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    parent_id  uuid,
    name       text        NOT NULL,
    path       text        NOT NULL,
    status     text        NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT uk_category_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_category_parent FOREIGN KEY (tenant_id, parent_id) REFERENCES catalog.category (tenant_id, id),
    CONSTRAINT ck_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uk_category_tenant_parent_name
    ON catalog.category (tenant_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
CREATE INDEX ix_category_tenant_path ON catalog.category (tenant_id, path text_pattern_ops);
ALTER TABLE catalog.category ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.category
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.category TO bmp_app;

-- Products (aggregate root of attribute types, exclusions and conversions).
CREATE TABLE catalog.product
(
    id              uuid        NOT NULL,
    tenant_id       uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    category_id     uuid,
    template        text        NOT NULL,
    base_uom_id     uuid        NOT NULL,
    track_batches   boolean     NOT NULL DEFAULT false,
    track_expiry    boolean     NOT NULL DEFAULT false,
    track_serials   boolean     NOT NULL DEFAULT false,
    decimal_qty     boolean     NOT NULL DEFAULT false,
    weighed         boolean     NOT NULL DEFAULT false,
    tax_category_id uuid,
    properties      jsonb       NOT NULL DEFAULT '{}',
    status          text        NOT NULL,
    revision        bigint      NOT NULL DEFAULT 0,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    created_by      uuid,
    updated_at      timestamptz NOT NULL,
    updated_by      uuid,
    CONSTRAINT pk_product PRIMARY KEY (id),
    CONSTRAINT uk_product_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_product_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_product_category FOREIGN KEY (tenant_id, category_id) REFERENCES catalog.category (tenant_id, id),
    CONSTRAINT fk_product_base_uom FOREIGN KEY (base_uom_id) REFERENCES catalog.unit_of_measure (id),
    CONSTRAINT ck_product_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_product_template CHECK (template IN ('SIMPLE', 'VARIANT_MATRIX', 'BULK_BREAK', 'BRANDED_PACKET',
                                                      'LOOSE_REPACK', 'RECIPE', 'SERVICE')),
    CONSTRAINT ck_product_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_product_properties CHECK (jsonb_typeof(properties) = 'object')
);
CREATE INDEX ix_product_tenant_category ON catalog.product (tenant_id, category_id);
CREATE INDEX ix_product_tenant_revision ON catalog.product (tenant_id, revision);
ALTER TABLE catalog.product ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.product FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.product
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.product TO bmp_app;

-- Collection tables are written by JPA without a tenant_id attribute: the default takes the transaction's tenant and
-- fails (BM001) when there is none. The composite foreign keys keep both ends in the same tenant.

-- Product conversions across dimensions (CAT-03, CAT-14): 1 from = factor to.
CREATE TABLE catalog.product_conversion
(
    tenant_id   uuid            NOT NULL DEFAULT platform.current_tenant_id(),
    product_id  uuid            NOT NULL,
    from_uom_id uuid            NOT NULL,
    to_uom_id   uuid            NOT NULL,
    factor      numeric(20, 10) NOT NULL,
    source      text            NOT NULL,
    CONSTRAINT pk_product_conversion PRIMARY KEY (product_id, from_uom_id, to_uom_id),
    CONSTRAINT fk_product_conversion_product FOREIGN KEY (tenant_id, product_id)
        REFERENCES catalog.product (tenant_id, id),
    CONSTRAINT ck_product_conversion_factor CHECK (factor > 0),
    CONSTRAINT ck_product_conversion_units CHECK (from_uom_id <> to_uom_id),
    CONSTRAINT ck_product_conversion_source CHECK (source IN ('DENSITY', 'MEASURED_YIELD', 'STANDARD'))
);
CREATE INDEX ix_product_conversion_tenant_product ON catalog.product_conversion (tenant_id, product_id);
ALTER TABLE catalog.product_conversion ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.product_conversion FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.product_conversion
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.product_conversion TO bmp_app;

-- Attribute types (Colour, Size) and their values; part of the product aggregate.
CREATE TABLE catalog.attribute_type
(
    id         uuid    NOT NULL,
    tenant_id  uuid    NOT NULL,
    product_id uuid    NOT NULL,
    code       text    NOT NULL,
    name       text    NOT NULL,
    sort       integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_attribute_type PRIMARY KEY (id),
    CONSTRAINT uk_attribute_type_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_attribute_type_product_code UNIQUE (product_id, code),
    CONSTRAINT fk_attribute_type_product FOREIGN KEY (tenant_id, product_id) REFERENCES catalog.product (tenant_id, id)
);
CREATE INDEX ix_attribute_type_tenant_product ON catalog.attribute_type (tenant_id, product_id);
ALTER TABLE catalog.attribute_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.attribute_type FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.attribute_type
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.attribute_type TO bmp_app;

CREATE TABLE catalog.attribute_value
(
    id                uuid    NOT NULL,
    tenant_id         uuid    NOT NULL,
    attribute_type_id uuid    NOT NULL,
    code              text    NOT NULL,
    value             text    NOT NULL,
    sort              integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_attribute_value PRIMARY KEY (id),
    CONSTRAINT uk_attribute_value_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_attribute_value_type_code UNIQUE (attribute_type_id, code),
    CONSTRAINT fk_attribute_value_type FOREIGN KEY (tenant_id, attribute_type_id)
        REFERENCES catalog.attribute_type (tenant_id, id)
);
CREATE INDEX ix_attribute_value_tenant_type ON catalog.attribute_value (tenant_id, attribute_type_id);
ALTER TABLE catalog.attribute_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.attribute_value FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.attribute_value
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.attribute_value TO bmp_app;

-- Combinations never to generate (CAT-05): a combination containing all these values is excluded.
CREATE TABLE catalog.variant_exclusion
(
    tenant_id           uuid   NOT NULL DEFAULT platform.current_tenant_id(),
    product_id          uuid   NOT NULL,
    attribute_value_ids uuid[] NOT NULL,
    CONSTRAINT pk_variant_exclusion PRIMARY KEY (product_id, attribute_value_ids),
    CONSTRAINT fk_variant_exclusion_product FOREIGN KEY (tenant_id, product_id)
        REFERENCES catalog.product (tenant_id, id),
    CONSTRAINT ck_variant_exclusion_values CHECK (cardinality(attribute_value_ids) >= 1)
);
CREATE INDEX ix_variant_exclusion_tenant_product ON catalog.variant_exclusion (tenant_id, product_id);
ALTER TABLE catalog.variant_exclusion ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.variant_exclusion FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.variant_exclusion
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.variant_exclusion TO bmp_app;

-- Variants (SKUs): aggregate root of packaging units and barcodes.
CREATE TABLE catalog.variant
(
    id           uuid        NOT NULL,
    tenant_id    uuid        NOT NULL,
    product_id   uuid        NOT NULL,
    sku          text        NOT NULL,
    name         text        NOT NULL,
    stock_states text[]      NOT NULL DEFAULT '{DEFAULT}',
    sell_mode    text        NOT NULL,
    status       text        NOT NULL,
    revision     bigint      NOT NULL DEFAULT 0,
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    created_by   uuid,
    updated_at   timestamptz NOT NULL,
    updated_by   uuid,
    CONSTRAINT pk_variant PRIMARY KEY (id),
    CONSTRAINT uk_variant_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_variant_tenant_sku UNIQUE (tenant_id, sku),
    CONSTRAINT fk_variant_product FOREIGN KEY (tenant_id, product_id) REFERENCES catalog.product (tenant_id, id),
    CONSTRAINT ck_variant_sku CHECK (sku ~ '^[A-Z0-9][A-Z0-9_.-]{0,63}$'),
    CONSTRAINT ck_variant_stock_states CHECK (cardinality(stock_states) BETWEEN 1 AND 20),
    CONSTRAINT ck_variant_sell_mode CHECK (sell_mode IN ('PREPACKED', 'BREAK_ON_SALE')),
    CONSTRAINT ck_variant_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_variant_tenant_product ON catalog.variant (tenant_id, product_id);
CREATE INDEX ix_variant_tenant_revision ON catalog.variant (tenant_id, revision);
ALTER TABLE catalog.variant ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.variant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.variant
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.variant TO bmp_app;

CREATE TABLE catalog.variant_attribute_value
(
    tenant_id          uuid NOT NULL DEFAULT platform.current_tenant_id(),
    variant_id         uuid NOT NULL,
    attribute_value_id uuid NOT NULL,
    CONSTRAINT pk_variant_attribute_value PRIMARY KEY (variant_id, attribute_value_id),
    CONSTRAINT fk_variant_attribute_value_variant FOREIGN KEY (tenant_id, variant_id)
        REFERENCES catalog.variant (tenant_id, id),
    CONSTRAINT fk_variant_attribute_value_value FOREIGN KEY (tenant_id, attribute_value_id)
        REFERENCES catalog.attribute_value (tenant_id, id)
);
CREATE INDEX ix_variant_attribute_value_tenant_value ON catalog.variant_attribute_value (tenant_id, attribute_value_id);
ALTER TABLE catalog.variant_attribute_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.variant_attribute_value FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.variant_attribute_value
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, DELETE ON catalog.variant_attribute_value TO bmp_app;

-- Packaging units (CAT-02, CAT-04, CAT-07): multiples of the base unit; exactly one base unit per variant.
CREATE TABLE catalog.packaging_unit
(
    id              uuid           NOT NULL,
    tenant_id       uuid           NOT NULL,
    variant_id      uuid           NOT NULL,
    code            text           NOT NULL,
    name            text           NOT NULL,
    qty_in_base     numeric(20, 6) NOT NULL,
    defined_qty     numeric(20, 6),
    defined_uom_id  uuid,
    stock_state     text           NOT NULL,
    is_base         boolean        NOT NULL,
    can_buy         boolean        NOT NULL,
    can_sell        boolean        NOT NULL,
    is_default_sell boolean        NOT NULL DEFAULT false,
    status          text           NOT NULL,
    CONSTRAINT pk_packaging_unit PRIMARY KEY (id),
    CONSTRAINT uk_packaging_unit_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_packaging_unit_variant FOREIGN KEY (tenant_id, variant_id) REFERENCES catalog.variant (tenant_id, id),
    CONSTRAINT fk_packaging_unit_defined_uom FOREIGN KEY (defined_uom_id) REFERENCES catalog.unit_of_measure (id),
    CONSTRAINT ck_packaging_unit_qty CHECK (qty_in_base > 0),
    CONSTRAINT ck_packaging_unit_base_qty CHECK (NOT is_base OR qty_in_base = 1),
    CONSTRAINT ck_packaging_unit_defined CHECK ((defined_qty IS NULL) = (defined_uom_id IS NULL)
                                                AND (defined_qty IS NULL OR defined_qty > 0)),
    CONSTRAINT ck_packaging_unit_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX uk_packaging_unit_variant_code ON catalog.packaging_unit (variant_id, lower(code));
CREATE UNIQUE INDEX uk_packaging_unit_variant_base ON catalog.packaging_unit (variant_id) WHERE is_base;
CREATE INDEX ix_packaging_unit_tenant_variant ON catalog.packaging_unit (tenant_id, variant_id);
ALTER TABLE catalog.packaging_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.packaging_unit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.packaging_unit
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.packaging_unit TO bmp_app;

-- Barcodes (CAT-12): unique per tenant, one barcode maps to exactly one packaging unit. Barcodes are lookup keys that
-- nothing refers to, so they are deleted when detached.
CREATE TABLE catalog.barcode
(
    tenant_id         uuid NOT NULL DEFAULT platform.current_tenant_id(),
    code              text NOT NULL,
    packaging_unit_id uuid NOT NULL,
    CONSTRAINT pk_barcode PRIMARY KEY (tenant_id, code),
    CONSTRAINT fk_barcode_packaging_unit FOREIGN KEY (tenant_id, packaging_unit_id)
        REFERENCES catalog.packaging_unit (tenant_id, id),
    CONSTRAINT ck_barcode_code CHECK (code ~ '^[A-Za-z0-9.-]{1,64}$')
);
CREATE INDEX ix_barcode_tenant_packaging_unit ON catalog.barcode (tenant_id, packaging_unit_id);
ALTER TABLE catalog.barcode ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.barcode FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.barcode
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, DELETE ON catalog.barcode TO bmp_app;

-- Transformation definitions (CAT-10): the current content; def_version is the latest published version.
CREATE TABLE catalog.transformation_definition
(
    id                 uuid          NOT NULL,
    tenant_id          uuid          NOT NULL,
    code               text          NOT NULL,
    name               text          NOT NULL,
    kind               text          NOT NULL,
    expected_yield_pct numeric(6, 3),
    def_version        integer       NOT NULL DEFAULT 0,
    status             text          NOT NULL,
    published_at       timestamptz,
    version            bigint        NOT NULL DEFAULT 0,
    created_at         timestamptz   NOT NULL,
    created_by         uuid,
    updated_at         timestamptz   NOT NULL,
    updated_by         uuid,
    CONSTRAINT pk_transformation_definition PRIMARY KEY (id),
    CONSTRAINT uk_transformation_definition_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_transformation_definition_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_transformation_definition_kind CHECK (kind IN ('OPEN', 'REPACK', 'BREAK', 'RECIPE')),
    CONSTRAINT ck_transformation_definition_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_transformation_definition_def_version CHECK (def_version >= 0),
    CONSTRAINT ck_transformation_definition_yield CHECK (expected_yield_pct IS NULL OR expected_yield_pct >= 0)
);
ALTER TABLE catalog.transformation_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.transformation_definition FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.transformation_definition
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON catalog.transformation_definition TO bmp_app;

-- Lines of the current content; replaced on edit (published versions live on in the version log below).
CREATE TABLE catalog.transformation_line
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    definition_id     uuid           NOT NULL,
    line_no           integer        NOT NULL,
    direction         text           NOT NULL,
    variant_id        uuid           NOT NULL,
    stock_state       text           NOT NULL,
    qty_base          numeric(20, 6) NOT NULL,
    packaging_unit_id uuid,
    qty               numeric(20, 6),
    cost_share_pct    numeric(6, 3),
    CONSTRAINT pk_transformation_line PRIMARY KEY (id),
    CONSTRAINT fk_transformation_line_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES catalog.transformation_definition (tenant_id, id),
    CONSTRAINT fk_transformation_line_variant FOREIGN KEY (tenant_id, variant_id) REFERENCES catalog.variant (tenant_id, id),
    CONSTRAINT fk_transformation_line_packaging_unit FOREIGN KEY (tenant_id, packaging_unit_id)
        REFERENCES catalog.packaging_unit (tenant_id, id),
    CONSTRAINT ck_transformation_line_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_transformation_line_qty CHECK (qty_base > 0 AND (qty IS NULL OR qty > 0)),
    CONSTRAINT ck_transformation_line_share CHECK (cost_share_pct IS NULL OR cost_share_pct BETWEEN 0 AND 100)
);
CREATE INDEX ix_transformation_line_tenant_definition ON catalog.transformation_line (tenant_id, definition_id);
CREATE INDEX ix_transformation_line_tenant_variant ON catalog.transformation_line (tenant_id, variant_id);
ALTER TABLE catalog.transformation_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.transformation_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.transformation_line
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.transformation_line TO bmp_app;

-- Every published version, frozen (CAT-10): append-only like a ledger.
CREATE TABLE catalog.transformation_definition_version
(
    tenant_id     uuid        NOT NULL,
    definition_id uuid        NOT NULL,
    def_version   integer     NOT NULL,
    published_at  timestamptz NOT NULL,
    published_by  uuid,
    snapshot      jsonb       NOT NULL,
    CONSTRAINT pk_transformation_definition_version PRIMARY KEY (definition_id, def_version),
    CONSTRAINT fk_transformation_definition_version_definition FOREIGN KEY (tenant_id, definition_id)
        REFERENCES catalog.transformation_definition (tenant_id, id),
    CONSTRAINT ck_transformation_definition_version_positive CHECK (def_version >= 1)
);
CREATE INDEX ix_transformation_definition_version_tenant ON catalog.transformation_definition_version
    (tenant_id, definition_id);
ALTER TABLE catalog.transformation_definition_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.transformation_definition_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog.transformation_definition_version
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON catalog.transformation_definition_version TO bmp_app;
