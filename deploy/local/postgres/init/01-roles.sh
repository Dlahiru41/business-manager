#!/bin/sh
# Creates the platform database roles (06-persistence-and-data.md §1). The official postgres image runs this once,
# on the first start with an empty data directory. Passwords come from the environment; the defaults are for local
# development and tests only.
#   bmp_migrator        owns the schemas and runs Flyway
#   bmp_app             what the application connects as: DML only, never owner, so row-level security applies
#   bmp_platform_admin  BYPASSRLS, for audited cross-tenant platform jobs only
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v dbname="$POSTGRES_DB" \
    -v migrator_password="${BMP_MIGRATOR_PASSWORD:-bmp_migrator}" \
    -v app_password="${BMP_APP_PASSWORD:-bmp_app}" \
    -v admin_password="${BMP_PLATFORM_ADMIN_PASSWORD:-bmp_platform_admin}" <<'EOSQL'
CREATE ROLE bmp_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE bmp_app LOGIN PASSWORD :'app_password';
CREATE ROLE bmp_platform_admin LOGIN BYPASSRLS PASSWORD :'admin_password';

GRANT CONNECT, CREATE, TEMPORARY ON DATABASE :"dbname" TO bmp_migrator;
GRANT CONNECT ON DATABASE :"dbname" TO bmp_app, bmp_platform_admin;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
EOSQL
