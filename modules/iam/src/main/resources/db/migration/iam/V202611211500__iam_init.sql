-- IAM module schema (docs/reference/data-model.md, section iam). Runs as bmp_migrator; bmp_app gets DML only.
-- Credentials (passwords, device secrets) live in the identity provider and are never stored here.
GRANT USAGE ON SCHEMA iam TO bmp_app;

-- A user profile. The id is the identity provider subject (ADR-014), so actor ids in every module link here.
CREATE TABLE iam.app_user
(
    id                  uuid        NOT NULL,
    tenant_id           uuid        NOT NULL,
    idp_subject         uuid        NOT NULL,
    display_name        text        NOT NULL,
    status              text        NOT NULL,
    default_location_id uuid,
    version             bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    created_by          uuid,
    updated_at          timestamptz NOT NULL,
    updated_by          uuid,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_app_user_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_app_user_idp_subject UNIQUE (idp_subject),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX ix_app_user_tenant_created_at ON iam.app_user (tenant_id, created_at, id);
ALTER TABLE iam.app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.app_user FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.app_user
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON iam.app_user TO bmp_app;

-- Roles of a tenant: the seeded system roles (IAM-02: never removed) and custom roles.
CREATE TABLE iam.role
(
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    system     boolean     NOT NULL DEFAULT false,
    status     text        NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by uuid,
    updated_at timestamptz NOT NULL,
    updated_by uuid,
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uk_role_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_role_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_role_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT ck_role_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
ALTER TABLE iam.role ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.role FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.role
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON iam.role TO bmp_app;

-- Collection tables are written by JPA without a tenant_id attribute: the default takes the transaction's tenant and
-- fails (BM001) when there is none. The composite foreign keys keep both ends in the same tenant.
CREATE TABLE iam.role_permission
(
    tenant_id  uuid NOT NULL DEFAULT platform.current_tenant_id(),
    role_id    uuid NOT NULL,
    permission text NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (tenant_id, role_id) REFERENCES iam.role (tenant_id, id)
);
CREATE INDEX ix_role_permission_tenant_role ON iam.role_permission (tenant_id, role_id);
ALTER TABLE iam.role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.role_permission FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.role_permission
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.role_permission TO bmp_app;

CREATE TABLE iam.user_role
(
    tenant_id      uuid   NOT NULL DEFAULT platform.current_tenant_id(),
    user_id        uuid   NOT NULL,
    role_id        uuid   NOT NULL,
    location_scope uuid[] NOT NULL DEFAULT '{}',
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (tenant_id, user_id) REFERENCES iam.app_user (tenant_id, id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (tenant_id, role_id) REFERENCES iam.role (tenant_id, id)
);
CREATE INDEX ix_user_role_tenant_role ON iam.user_role (tenant_id, role_id);
ALTER TABLE iam.user_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.user_role FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.user_role
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.user_role TO bmp_app;

-- A registered till. Revocation is a state change kept for the audit trail; devices are never deleted.
CREATE TABLE iam.device
(
    id            uuid        NOT NULL,
    tenant_id     uuid        NOT NULL,
    name          text        NOT NULL,
    register_id   uuid,
    location_id   uuid        NOT NULL,
    idp_client_id text        NOT NULL,
    status        text        NOT NULL,
    registered_at timestamptz NOT NULL,
    revoked_at    timestamptz,
    revoke_reason text,
    last_seen_at  timestamptz,
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL,
    created_by    uuid,
    updated_at    timestamptz NOT NULL,
    updated_by    uuid,
    CONSTRAINT pk_device PRIMARY KEY (id),
    CONSTRAINT uk_device_idp_client_id UNIQUE (idp_client_id),
    CONSTRAINT ck_device_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_device_revocation CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE INDEX ix_device_tenant_location ON iam.device (tenant_id, location_id);
ALTER TABLE iam.device ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.device FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.device
    USING (tenant_id = platform.current_tenant_id())
    WITH CHECK (tenant_id = platform.current_tenant_id());
GRANT SELECT, INSERT, UPDATE ON iam.device TO bmp_app;
