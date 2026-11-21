# ADR-015: Event publication style

- Status: accepted
- Date: 2026-11-21
- Phase / task: 00 / T00-07 (07-events-and-messaging.md §3)

## Context
07-events-and-messaging.md §3 asks for one publication style across the codebase: either handlers publish through
`ApplicationEventPublisher` directly, or aggregates collect events that the handler publishes after `save`. Either
way, the event is recorded in the Spring Modulith publication registry inside the business transaction (the outbox),
and delivered only if that transaction commits.

## Options considered
1. Handlers build and publish every event. Pros: simple. Cons: the rule "what happened" drifts away from the aggregate
   method that made it happen, and events can be forgotten when a second handler changes the same aggregate.
2. **Aggregates record events; handlers publish them after saving.** Pros: the aggregate that changes state also
   states the fact, and tests of the aggregate see its events. Cons: aggregates need a recorder and envelope data
   (event id, time, metadata).

## Decision
Option 2.
- An aggregate keeps a `@Transient EventRecorder` (shared kernel) and records events in its intention-revealing
  methods (`Tenant.create`, `Location.create`). Envelope values come from an `EventStampSource` passed in by the
  handler, so domain code needs no clock, id generator or Spring. The production `EventStamps` uses UUIDv7, the
  injected `Clock` and the current actor, request and `traceparent`.
- The handler saves, then calls `aggregate.pullEvents().forEach(publisher::publishEvent)` inside its transaction.
- State that is not an aggregate (business settings) has its event built by the handler from an `EventStampSource`.
  That is the only exception, and it uses the same stamps.
- `aggregateVersion` is the version after the change: 0 for creation, the new group version for `SettingsChanged`.
- Listeners are `@ApplicationModuleListener`. A platform advisor binds the event's tenant, actor, MDC and trace
  before the listener transaction starts; listeners use `InboxGuard` for idempotency.

## Consequences
- Every aggregate test can assert on the events it records, without Spring.
- Phase 06 externalization only adds `@Externalized` to the event records. Publishers and listeners stay unchanged.
