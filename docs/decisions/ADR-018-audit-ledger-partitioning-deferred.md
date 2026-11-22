# ADR-018: Audit ledger partitioning deferred

- Status: accepted
- Date: 2026-11-22
- Phase / task: 00 / T00-09 (06-persistence-and-data.md §6, phase review)

## Context
06 §6 asks large ledgers to be time-partitioned from the start. `audit.audit_record` grows with every API write and
domain event. Its primary key is the record id and its chain is ordered by `(tenant_id, sequence)`; PostgreSQL
requires the partition key in every unique constraint, so partitioning by `occurred_at` changes those constraints.

## Options considered
1. Partition by month on `occurred_at` now. Cons: unique keys must include `occurred_at`, which weakens the
   gap-free `(tenant_id, sequence)` guarantee, and partition maintenance (pg_partman or a job) is needed before there
   is any volume to justify it.
2. **Keep one table in phase 00, with indexes leading on `tenant_id`, and partition before go-live.**

## Decision
Option 2. Partitioning (monthly, by `occurred_at`, with the chain key enforced by the append lock instead of a
unique index) is planned with the retention settings of phase 04. Until then every query carries an explicit
`tenant_id` predicate besides row-level security.

## Consequences
- A migration converts the table to a partitioned one in phase 04; the append-only grants and RLS move with it.
