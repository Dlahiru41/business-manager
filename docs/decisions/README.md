# Architecture Decision Records

Write one file per decision: `ADR-NNN-short-title.md`, numbered in order. Never edit an accepted ADR; supersede it with a new one.

Already decided (from the solution architecture; do not re-open without a new ADR):

| # | Decision |
| --- | --- |
| ADR-001 | Modular monolith (Spring Modulith) first, with service-ready boundaries; extract services in phase 06 only on triggers. |
| ADR-002 | Product model: Product → Variant → base unit + packaging units + transformations, configured by templates. |
| ADR-003 | Stock is an append-only movement ledger with balance projections. |
| ADR-004 | Inventory computes cost of goods and publishes it on `StockIssued`. |
| ADR-005 | Events for state changes (outbox), synchronous APIs for queries. |
| ADR-006 | Local-first POS with device-generated UUIDv7 ids and idempotent sync. |
| ADR-007 | Pooled multi-tenancy with `tenant_id` + PostgreSQL RLS. |
| ADR-008 | Decimal strings in APIs, `NUMERIC` in storage, `BigDecimal` in code. |
| ADR-009 | Double-entry ledger is the source of financial truth. |
| ADR-010 | Local Java print agent speaking ESC/POS. |

## Template

```markdown
# ADR-NNN: <title>

- Status: proposed | accepted | superseded by ADR-MMM
- Date: YYYY-MM-DD
- Phase / task: NN / task id

## Context
What forced a decision. Facts only.

## Options considered
1. ... — pros / cons
2. ... — pros / cons

## Decision
What we chose and the one main reason.

## Consequences
What becomes easier, what becomes harder, what to revisit and when.
```
