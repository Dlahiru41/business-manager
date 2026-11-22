# ADR-013: Fail-closed row-level security and the platform-admin path

- Status: accepted
- Date: 2026-11-21
- Phase / task: 00 / T00-04

## Context
06-persistence-and-data.md §4 requires that touching tenant-owned data without a tenant throws
`TENANT_CONTEXT_MISSING`, never "all tenants". Its sample policy uses
`current_setting('app.tenant_id', true)::uuid`. That expression returns NULL (no rows) when the setting was never
set. On a pooled connection where an earlier transaction set it, it returns `''`, and casting that to uuid fails
with a generic SQL error. Neither behaviour clearly says "missing tenant". Some platform work also legitimately
crosses tenants: purging expired idempotency records, and later failed-event inspection.

## Options considered
1. Policy expression from the standard as is. Cons: a missing tenant is silent (empty results) or an unrelated cast
   error.
2. **A `platform.current_tenant_id()` function used by every policy**, raising SQLSTATE `BM001` with the message
   `TENANT_CONTEXT_MISSING` when no tenant is set. Pros: fails closed and loudly on every read or write of tenant
   rows, from JPA and native SQL alike. Cons: one plpgsql call per statement (STABLE, so it is still usable in index
   conditions).

## Decision
Option 2, plus:
- `TenantAwareJpaTransactionManager` sets `app.tenant_id` transaction-locally (`set_config(..., true)`) right after
  each transaction begins, so JPA and `JdbcClient` share it and pooled connections never leak it. Outside a
  transaction nothing is set, so tenant data cannot be read without one.
- Hibernate's `CurrentTenantIdentifierResolver` returns the nil UUID when no tenant is bound, so transactions on
  platform tables (event publications, ShedLock) still work. The RLS policies remain the authority. This is defence
  in depth: Hibernate discriminator, then RLS, then handlers loading by `(tenantId, id)`.
- Policies use `FORCE ROW LEVEL SECURITY`, so they hold even for the table owner.
- Cross-tenant platform jobs use `PlatformAdminJdbc`. It connects as `bmp_platform_admin` (`BYPASSRLS`), holds only
  the grants such jobs need, and is not a `DataSource` bean, so nothing uses it by accident.
- Idempotency keys sent without a tenant (platform administrators creating tenants) are scoped to the nil-UUID tenant.
- `GlobalExceptionHandler` maps SQLSTATE `BM001` to a 500 with code `TENANT_CONTEXT_MISSING`.

## Consequences
- `RlsCoverage` (test-support) checks, for every module, that each `tenant_id` table has RLS enabled and forced with
  a policy, and that ledger tables grant no UPDATE/DELETE to `bmp_app`.
- On an empty table a sequential scan never evaluates the policy, so a tenant-less read returns no rows instead of
  failing. That is still fail-closed: nothing can leak.
- Tenant switching inside one transaction is not supported. Code that needs another tenant starts a new transaction.
