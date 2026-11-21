# 06 — Persistence and data

## 1. Database layout

- PostgreSQL 18. One database `bmp`, **one schema per module** (`catalog`, `pricing`, `inventory`, ...), plus `platform` for shared infrastructure tables (idempotency, ShedLock, event publication registry).
- A module's code only touches its own schema. ArchUnit + a test that scans SQL/JPA mappings for foreign schema names enforce it.
- No foreign keys across schemas. Cross-module references are plain `UUID` columns.
- Two DB roles: `bmp_migrator` (owns schemas, runs Flyway) and `bmp_app` (DML only, **not** owner, so RLS applies). The app never connects as owner in any environment except unit tests that do not touch RLS.

## 2. Migrations (Flyway)

- Each module has its own migration location `classpath:db/migration/<module>` and its own Flyway history table in its schema (configure one `Flyway` bean per module via a small `ModuleFlywayConfigurer` in `persistence-support`, run in dependency order at startup and in the migrator job).
- File names: `V<yyyyMMddHHmm>__<module>_<description>.sql` (timestamp versions avoid merge clashes).
- Migrations are **forward-only** and **expand-and-contract**: add column nullable → backfill → enforce constraint in a later release; never rename/drop in the same release that stops using a column.
- Every new tenant-owned table in a migration must include, in the same file:

```sql
ALTER TABLE inventory.stock_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_movement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory.stock_movement
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
GRANT SELECT, INSERT ON inventory.stock_movement TO bmp_app;   -- ledger: no UPDATE/DELETE
```

- A test (`RlsCoverageIT`) queries `pg_tables`/`pg_policies` and fails if any table in a module schema with a `tenant_id` column lacks RLS + policy, or if a ledger table grants UPDATE/DELETE to `bmp_app`.

## 3. Column conventions

| Column | Type | Rule |
| --- | --- | --- |
| `id` | `uuid` | UUIDv7 generated in Java (`IdGenerator`), not by the DB |
| `tenant_id` | `uuid not null` | First column of every index and unique constraint |
| `version` | `bigint not null default 0` | Optimistic locking (`@Version`) on mutable aggregates |
| `created_at`, `updated_at` | `timestamptz not null` | Set by JPA auditing / `Clock` |
| `created_by`, `updated_by` | `uuid` | Actor id |
| money | `numeric(19,4)` + `currency char(3)` | |
| unit cost | `numeric(19,6)` | |
| quantity | `numeric(20,6)` | Always in the variant's base unit when named `qty_base` |
| enums | `text` + `CHECK` constraint | Not PostgreSQL enum types (hard to evolve) |
| flexible attributes | `jsonb` | Validated by a JSON schema in code; never for data you query by |

Naming: tables singular `snake_case`; PK `pk_<table>`; unique `uk_<table>_<cols>`; index `ix_<table>_<cols>`; check `ck_<table>_<rule>`. Constraint names are mapped to error codes (see `04-error-handling.md` §3).

## 4. Multi-tenancy in the persistence layer

Defence in depth, three layers:
1. **Hibernate discriminator**: every tenant-owned entity has `@TenantId UUID tenantId;` and a `CurrentTenantIdentifierResolver` reading `TenantContext`. Hibernate adds the filter to every query and sets the value on insert.
2. **PostgreSQL RLS**: `TenantAwareDataSource` (in `persistence-support`) wraps the Hikari pool; on each connection checkout inside a transaction it executes `SELECT set_config('app.tenant_id', ?, true)` (transaction-local) — implement via a `TransactionSynchronization`/connection preparer so it runs once per transaction. For `JdbcClient` code the same applies because it shares the transaction.
3. **Application checks**: handlers load aggregates by `(tenantId, id)`.

If `TenantContext` is empty when a tenant-owned table is accessed, throw `TechnicalException(TENANT_CONTEXT_MISSING)` — never default to "all tenants". Platform-admin operations that legitimately span tenants use a dedicated `bmp_platform_admin` role and a separate, audited code path.

## 5. JPA usage

- `spring.jpa.open-in-view=false` (mandatory).
- `hibernate.jdbc.batch_size=50`, `order_inserts=true`, `order_updates=true`.
- Lazy associations everywhere; load what a use case needs with fetch joins or entity graphs in the repository method.
- Repositories are interfaces in `domain/port` (e.g. `VariantRepository`) implemented in `adapter/out/persistence` by Spring Data interfaces extending them. Domain code sees only the port.
- No `findAll()` without pagination. No derived queries longer than 3 conditions; write JPQL or SQL.
- Projections (records) for read models; never return entities from query services.

## 6. Ledgers and projections (`JdbcClient`)

- Ledger tables: insert-only. Writer classes (e.g. `StockMovementWriter`) use `JdbcClient` with text-block SQL and batch inserts.
- Projections (`stock_balance`, `party_balance`) are updated **in the same transaction** as the ledger insert:
  - Lock the balance row: `SELECT ... FOR UPDATE` (create it with `INSERT ... ON CONFLICT DO NOTHING` first).
  - Apply the delta, recompute average cost where applicable, update.
- Every projection has a **rebuild** command (admin endpoint + test) that recomputes it from the ledger; an integration test asserts rebuild == incremental result.
- Partition large ledgers by time (`PARTITION BY RANGE (occurred_at)`, monthly) from the start; create partitions ahead with a scheduled job (or `pg_partman` if available).

## 7. Transactions

- Isolation: READ COMMITTED (default) plus row locks where needed. Do not raise to SERIALIZABLE globally.
- Keep transactions short; never call remote systems inside them.
- Lock ordering to avoid deadlocks: when locking several balance rows, lock in ascending `(variant_id, location_id, stock_state, batch_id)` order.
- Number sequences (bill numbers per register, PO numbers per tenant) via a `number_sequence` table with `UPDATE ... RETURNING` in the same transaction — never PostgreSQL sequences per tenant.

## 8. Platform tables (schema `platform`)

- `idempotency_record` (see `03-api-standards.md` §5)
- `shedlock`
- Spring Modulith `event_publication` (JDBC registry; let Modulith create/migrate it through a Flyway migration you add, schema `platform`)
- `inbox` (see `07-events-and-messaging.md`)
- `failed_event`

## 9. Data retention, backup, privacy

- Soft delete master data (`status`, `deactivated_at`); never physically delete referenced data.
- Personal data columns are listed in each module README; provide an anonymisation command per module for erasure requests (replace PII with placeholders, keep financial records).
- Retention periods are tenant settings; purge jobs only touch data past retention and log a summary.

## 10. Testing persistence

- Integration tests use **Testcontainers PostgreSQL 18** with the real migrations and the `bmp_app` role (so RLS is exercised). Use a singleton container per JVM (`libs/test-support`).
- A `TenantIsolationIT` per module: create data as tenant A, switch to tenant B, assert it is invisible through API, JPA and `JdbcClient` paths.
- Never mock repositories in integration tests; mock them only in pure application-layer unit tests if at all (prefer in-memory fakes implementing the port).
