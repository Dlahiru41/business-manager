-- Pricing module schema (docs/reference/data-model.md, section pricing; phase 01, PRC-01..PRC-10).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
GRANT USAGE ON SCHEMA pricing TO bmp_app;

-- Price lists (PRC-01): a channel, optionally one customer group or one customer; current_version is the price
-- version bills record (PRC-03, PRC-10).
CREATE TABLE pricing.price_list
(
    id                uuid        NOT NULL,
    tenant_id         uuid        NOT NULL,
    code              text        NOT NULL,
    name              text        NOT NULL,
    channel           text        NOT NULL,
    customer_group_id uuid,
    customer_id       uuid,
    currency          text        NOT NULL,
    tax_inclusive     boolean     NOT NULL,
    priority          integer     NOT NULL DEFAULT 100,
    current_version   integer     NOT NULL DEFAULT 0,
    status            text        NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL,
    created_by        uuid,
    updated_at        timestamptz NOT NULL,
    updated_by        uuid,
    CONSTRAINT pk_price_list PRIMARY KEY (id),
    CONSTRAINT uk_price_list_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_price_list_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_price_list_channel CHECK (channel IN ('RETAIL', 'WHOLESALE', 'ANY')),
    CONSTRAINT ck_price_list_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_price_list_scope CHECK (customer_group_id IS NULL OR customer_id IS NULL),
    CONSTRAINT ck_price_list_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_price_list_priority CHECK (priority >= 0),
    CONSTRAINT ck_price_list_current_version CHECK (current_version >= 0)
);
CREATE INDEX ix_price_list_tenant_priority ON pricing.price_list (tenant_id, priority, code);
ALTER TABLE pricing.price_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.price_list FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing.price_list
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON pricing.price_list TO bmp_app;

-- Prices (PRC-02): a packaging unit of a variant from a minimum quantity during [valid_from, valid_to). Windows of the
-- same list, unit and minimum quantity never overlap: checked under the price list row lock taken by every upsert.
CREATE TABLE pricing.price
(
    id                uuid           NOT NULL,
    tenant_id         uuid           NOT NULL,
    price_list_id     uuid           NOT NULL,
    variant_id        uuid           NOT NULL,
    packaging_unit_id uuid           NOT NULL,
    min_qty           numeric(20, 6) NOT NULL DEFAULT 1,
    amount            numeric(19, 4) NOT NULL,
    valid_from        timestamptz    NOT NULL,
    valid_to          timestamptz,
    list_version      integer        NOT NULL,
    version           bigint         NOT NULL DEFAULT 0,
    CONSTRAINT pk_price PRIMARY KEY (id),
    CONSTRAINT uk_price_list_unit_min_qty_from UNIQUE (price_list_id, packaging_unit_id, min_qty, valid_from),
    CONSTRAINT fk_price_price_list FOREIGN KEY (tenant_id, price_list_id) REFERENCES pricing.price_list (tenant_id, id),
    CONSTRAINT ck_price_min_qty CHECK (min_qty > 0),
    CONSTRAINT ck_price_amount CHECK (amount >= 0),
    CONSTRAINT ck_price_validity CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_price_list_version CHECK (list_version >= 1)
);
CREATE INDEX ix_price_tenant_list_unit ON pricing.price (tenant_id, price_list_id, packaging_unit_id);
CREATE INDEX ix_price_tenant_list_version ON pricing.price (tenant_id, price_list_id, list_version);
CREATE INDEX ix_price_tenant_unit ON pricing.price (tenant_id, packaging_unit_id);
ALTER TABLE pricing.price ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.price FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing.price
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON pricing.price TO bmp_app;

-- Every version of a list with its change summary (PRC-03): append-only.
CREATE TABLE pricing.price_list_version
(
    tenant_id      uuid        NOT NULL,
    price_list_id  uuid        NOT NULL,
    version        integer     NOT NULL,
    published_at   timestamptz NOT NULL,
    published_by   uuid,
    note           text,
    change_count   integer     NOT NULL,
    change_summary jsonb       NOT NULL,
    CONSTRAINT pk_price_list_version PRIMARY KEY (price_list_id, version),
    CONSTRAINT fk_price_list_version_price_list FOREIGN KEY (tenant_id, price_list_id)
        REFERENCES pricing.price_list (tenant_id, id),
    CONSTRAINT ck_price_list_version_positive CHECK (version >= 1),
    CONSTRAINT ck_price_list_version_summary CHECK (jsonb_typeof(change_summary) = 'array')
);
CREATE INDEX ix_price_list_version_tenant_list ON pricing.price_list_version (tenant_id, price_list_id, version);
ALTER TABLE pricing.price_list_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.price_list_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing.price_list_version
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT ON pricing.price_list_version TO bmp_app;

-- Price rules (PRC-06). Conditions are typed columns (arrays, empty = any) rather than a JSON document: resolution
-- evaluates them for every line and they have a closed shape (ADR-021).
CREATE TABLE pricing.price_rule
(
    id                      uuid           NOT NULL,
    tenant_id               uuid           NOT NULL,
    code                    text           NOT NULL,
    name                    text           NOT NULL,
    kind                    text           NOT NULL,
    cond_channel            text,
    cond_price_list_ids     uuid[]         NOT NULL DEFAULT '{}',
    cond_customer_group_ids uuid[]         NOT NULL DEFAULT '{}',
    cond_customer_ids       uuid[]         NOT NULL DEFAULT '{}',
    cond_product_ids        uuid[]         NOT NULL DEFAULT '{}',
    cond_variant_ids        uuid[]         NOT NULL DEFAULT '{}',
    cond_packaging_unit_ids uuid[]         NOT NULL DEFAULT '{}',
    cond_min_qty_base       numeric(20, 6),
    effect_type             text           NOT NULL,
    effect_value            numeric(19, 4) NOT NULL,
    priority                integer        NOT NULL DEFAULT 100,
    stackable               boolean        NOT NULL DEFAULT false,
    valid_from              timestamptz,
    valid_to                timestamptz,
    status                  text           NOT NULL,
    version                 bigint         NOT NULL DEFAULT 0,
    created_at              timestamptz    NOT NULL,
    created_by              uuid,
    updated_at              timestamptz    NOT NULL,
    updated_by              uuid,
    CONSTRAINT pk_price_rule PRIMARY KEY (id),
    CONSTRAINT uk_price_rule_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_price_rule_kind CHECK (kind IN ('QTY_BREAK', 'CUSTOMER_GROUP', 'PROMOTION', 'MARKUP')),
    CONSTRAINT ck_price_rule_channel CHECK (cond_channel IS NULL OR cond_channel IN ('RETAIL', 'WHOLESALE')),
    CONSTRAINT ck_price_rule_effect CHECK (effect_type IN ('PERCENT_OFF', 'AMOUNT_OFF', 'FIXED_PRICE', 'MARKUP_PERCENT')
                                           AND effect_value >= 0),
    CONSTRAINT ck_price_rule_validity CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_price_rule_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_price_rule_tenant_priority ON pricing.price_rule (tenant_id, priority, code);
ALTER TABLE pricing.price_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.price_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing.price_rule
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON pricing.price_rule TO bmp_app;

-- Tax categories and their dated rates (PRC-07).
CREATE TABLE pricing.tax_category
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    CONSTRAINT pk_tax_category PRIMARY KEY (id),
    CONSTRAINT uk_tax_category_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_tax_category_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_tax_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
ALTER TABLE pricing.tax_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.tax_category FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing.tax_category
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON pricing.tax_category TO bmp_app;

CREATE TABLE pricing.tax_rate
(
    id              uuid          NOT NULL,
    tenant_id       uuid          NOT NULL,
    tax_category_id uuid          NOT NULL,
    code            text          NOT NULL,
    name            text          NOT NULL,
    rate_pct        numeric(7, 4) NOT NULL,
    compound        boolean       NOT NULL DEFAULT false,
    valid_from      date          NOT NULL,
    valid_to        date,
    version         bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL,
    created_by      uuid,
    updated_at      timestamptz   NOT NULL,
    updated_by      uuid,
    CONSTRAINT pk_tax_rate PRIMARY KEY (id),
    CONSTRAINT fk_tax_rate_tax_category FOREIGN KEY (tenant_id, tax_category_id)
        REFERENCES pricing.tax_category (tenant_id, id),
    CONSTRAINT ck_tax_rate_pct CHECK (rate_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_tax_rate_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX ix_tax_rate_tenant_category ON pricing.tax_rate (tenant_id, tax_category_id, valid_from);
ALTER TABLE pricing.tax_rate ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.tax_rate FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing.tax_rate
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON pricing.tax_rate TO bmp_app;

-- Local replica of catalog variants, from catalog events (T01-05). aggregate_version orders snapshots: an older event
-- never overwrites a newer one; a deactivation that overtakes the creation leaves an inactive tombstone.
CREATE TABLE pricing.variant_price_ref
(
    variant_id        uuid        NOT NULL,
    tenant_id         uuid        NOT NULL,
    product_id        uuid        NOT NULL,
    tax_category_id   uuid,
    status            text        NOT NULL,
    aggregate_version bigint      NOT NULL,
    packaging_units   jsonb       NOT NULL DEFAULT '[]',
    updated_at        timestamptz NOT NULL,
    CONSTRAINT pk_variant_price_ref PRIMARY KEY (variant_id),
    CONSTRAINT ck_variant_price_ref_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_variant_price_ref_units CHECK (jsonb_typeof(packaging_units) = 'array')
);
CREATE INDEX ix_variant_price_ref_tenant_product ON pricing.variant_price_ref (tenant_id, product_id);
ALTER TABLE pricing.variant_price_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.variant_price_ref FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing.variant_price_ref
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON pricing.variant_price_ref TO bmp_app;
