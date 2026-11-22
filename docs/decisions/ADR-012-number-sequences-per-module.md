# ADR-012: Document number sequences owned by each module

- Status: accepted
- Date: 2026-11-21
- Phase / task: 00 / T00-07 (helper built in T00-04)

## Context
Business documents (purchase order, goods receipt, credit note) need human-readable numbers that are unique per
tenant and allocated in the **same transaction** as the document, so a rolled-back document never consumes or
duplicates a number. Modules may not touch another module's tables or call another module synchronously inside their
transaction (01-architecture-and-code-structure.md §2). Per-tenant PostgreSQL sequences are ruled out by
06-persistence-and-data.md §7. Bill numbers are different: devices allocate them per register while offline
(phase 03).

## Options considered
1. A central `tenant.number_sequence` table behind a tenant-module API. Pros: one table. Cons: the caller's
   transaction would write into another module's schema (breaks module ownership), and a remote call in phase 06
   could not share the transaction.
2. One PostgreSQL sequence per tenant and scope. Cons: sequences are not transactional (gaps on rollback), DDL per
   tenant, forbidden by the standard.
3. **Each module owns `<schema>.number_sequence (tenant_id, scope, prefix, next_value)`**, allocated with
   `UPDATE ... RETURNING` in the document's transaction. Pros: transactional, no cross-module writes, survives service
   extraction unchanged. Cons: the same small table exists in several schemas.

## Decision
Option 3. `persistence-support` provides `NumberSequences` (`new NumberSequences(jdbcClient, "procurement")`). It calls
`platform.next_number(regclass, tenant, scope)`, which locks the row with `UPDATE ... RETURNING` and creates a missing
scope at 1. The table name is passed as `regclass`, so the dynamic SQL inside the function cannot be injected. The
Java side is fully parameterized. Row-level security applies, because the function runs with the caller's rights.
`NumberSequences.next` requires an existing transaction (`Propagation.MANDATORY`).

The tenant module stores only the per-tenant **prefixes and formats** as settings (group `numbering`, e.g.
`purchaseOrderPrefix`); the owning module formats `prefix + number`.

## Consequences
- Concurrent allocations for one scope serialize on that row lock. That is acceptable for back-office documents. It
  would not be for POS bills, which is why those are allocated on the device.
- A rolled-back transaction returns its number, so stored numbers stay gap-free. Proven by
  `PersistenceInfrastructureIT`.
- Every module that numbers documents adds the table, with RLS, in its own migration.
