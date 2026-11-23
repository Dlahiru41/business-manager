-- Party module schema (docs/reference/data-model.md, section party; phase 02, PTY-01..PTY-04).
-- Runs as bmp_migrator; bmp_app gets DML only, so row-level security applies to it.
-- Departures from the logical model (ADR-022): the address and the credit policy are columns of party (one
-- aggregate, fixed parts, anonymised in place); name search uses a trigram index, which cannot lead with tenant_id.
GRANT USAGE ON SCHEMA party TO bmp_app;

-- Trusted extension (creatable by the database's CREATE privilege); kept in the platform schema for every module.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA platform;

-- Customer groups: codes unique per tenant.
CREATE TABLE party.customer_group
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
    CONSTRAINT pk_customer_group PRIMARY KEY (id),
    CONSTRAINT uk_customer_group_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_customer_group_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
ALTER TABLE party.customer_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE party.customer_group FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON party.customer_group
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON party.customer_group TO bmp_app;

-- Parties (PTY-01): display name, phone (E.164), e-mail, tax id and address are personal data (PTY-04).
CREATE TABLE party.party
(
    id                  uuid           NOT NULL,
    tenant_id           uuid           NOT NULL,
    type                text           NOT NULL,
    display_name        text           NOT NULL,
    phone               text,
    email               text,
    tax_id              text,
    address_line1       text,
    address_line2       text,
    address_city        text,
    address_district    text,
    address_postal_code text,
    address_country     text,
    customer_group_id   uuid,
    status              text           NOT NULL,
    anonymised          boolean        NOT NULL DEFAULT false,
    anonymised_at       timestamptz,
    credit_allowed      boolean        NOT NULL DEFAULT false,
    credit_limit        numeric(19, 4) NOT NULL DEFAULT 0,
    payment_terms_days  integer        NOT NULL DEFAULT 0,
    version             bigint         NOT NULL DEFAULT 0,
    created_at          timestamptz    NOT NULL,
    created_by          uuid,
    updated_at          timestamptz    NOT NULL,
    updated_by          uuid,
    CONSTRAINT pk_party PRIMARY KEY (id),
    CONSTRAINT fk_party_customer_group FOREIGN KEY (customer_group_id) REFERENCES party.customer_group (id),
    CONSTRAINT ck_party_type CHECK (type IN ('CUSTOMER', 'SUPPLIER', 'BOTH')),
    CONSTRAINT ck_party_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_party_phone CHECK (phone IS NULL OR phone ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_party_country CHECK (address_country IS NULL OR address_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_party_credit_limit CHECK (credit_limit >= 0),
    CONSTRAINT ck_party_payment_terms CHECK (payment_terms_days BETWEEN 0 AND 365),
    CONSTRAINT ck_party_anonymised CHECK (NOT anonymised OR (phone IS NULL AND email IS NULL AND tax_id IS NULL
        AND address_line1 IS NULL AND address_line2 IS NULL AND address_city IS NULL AND address_district IS NULL
        AND address_postal_code IS NULL AND address_country IS NULL))
);
CREATE INDEX ix_party_tenant_id ON party.party (tenant_id, id DESC);
CREATE INDEX ix_party_tenant_phone ON party.party (tenant_id, phone text_pattern_ops);
CREATE INDEX ix_party_tenant_group ON party.party (tenant_id, customer_group_id);
CREATE INDEX ix_party_name_trgm ON party.party USING gin (lower(display_name) platform.gin_trgm_ops);
ALTER TABLE party.party ENABLE ROW LEVEL SECURITY;
ALTER TABLE party.party FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON party.party
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON party.party TO bmp_app;
