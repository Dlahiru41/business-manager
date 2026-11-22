# ADR-019: Catalog snapshot events and catalog versions

- Status: accepted
- Date: 2026-11-22
- Phase / task: 01 / T01-04

## Context
CAT-13 asks for `ProductUpdated` / `VariantUpdated` with full packaging-unit snapshots so pricing, inventory, sales and
sync keep replicas. ADR-015 lets aggregates record events and handlers publish them after save, but a snapshot event
must carry the aggregate version the change produced, and with JPA the version (and the ids of child rows) is only
final after the flush. Tills also need deltas of the catalog "since version N" (catalog snapshot endpoint), and the
deltas must not miss a change committed out of order.

## Options considered
1. Aggregates record snapshot events before saving — the version in the event is the old one; consumers ordering by
   `aggregateVersion` would drop real updates.
2. Handlers build snapshot events from the saved, flushed aggregate (application layer `CatalogEvents`) — the version
   is exact; the domain stays free of event assembly for read-model data such as unit codes.
3. A database sequence for the catalog version — values are taken out of commit order, so a delta query `revision >
   N` can miss a transaction that took a smaller value but committed later.

## Decision
Option 2 for events: catalog handlers save and flush, then `CatalogEvents` publishes snapshots built from the stored
aggregates, inside the same transaction (outbox delivery after commit is unchanged). For deltas, a per-tenant row
`catalog.catalog_revision` is incremented (`INSERT ... ON CONFLICT DO UPDATE ... RETURNING`) by every catalog change;
the row lock serializes catalog writers of one tenant, so revisions become visible in order. Every changed product
and variant stores the revision it was changed in.

## Consequences
- `aggregateVersion` in catalog events is exactly the stored version; consumers keep the snapshot with the highest
  one.
- Catalog writes of one tenant are serialized (back-office writes; acceptable). If bulk imports (phase 05) make this a
  bottleneck, take the revision once per import transaction.
- The snapshot endpoint is not paginated; revisit when a tenant's catalog exceeds a few thousand variants.
