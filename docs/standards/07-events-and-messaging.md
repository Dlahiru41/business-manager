# 07 — Events and messaging

Cross-module side effects happen through **domain events**. Phases 00–05 run events in-process with Spring Modulith's durable event publication registry (a transactional outbox). Phase 06 externalizes the same events to Kafka without changing publishers or listeners.

## 1. Event definition

- Events are **records** in the publishing module's `api/events` package, named in the past tense.
- Every event implements `com.bmp.shared.event.DomainEvent`:

```java
public interface DomainEvent {
    UUID eventId();            // UUIDv7, generated at creation
    TenantId tenantId();
    Instant occurredAt();
    UUID aggregateId();
    long aggregateVersion();   // for ordering / de-duplication
    EventMetadata metadata();  // actorId, deviceId, requestId, traceparent
}

public record SaleCompleted(
        UUID eventId, TenantId tenantId, Instant occurredAt, UUID aggregateId, long aggregateVersion,
        EventMetadata metadata,
        BillId billId, String billNumber, SalesChannel channel, @Nullable CustomerId customerId,
        LocationId locationId, List<Line> lines, Totals totals, int priceListVersion, DeviceId deviceId
) implements DomainEvent {
    public record Line(BillLineId lineId, VariantId variantId, PackagingUnitId unitId, BigDecimal qty,
                       BigDecimal qtyBase, String stockState, Money unitPrice, Money discount, Money tax, Money lineTotal) {}
}
```

- Events carry **all data consumers need** to act without calling back (event-carried state transfer), but no PII beyond ids.
- Event type names for externalization: `bmp.<module>.<event-kebab>.v<N>`, e.g. `bmp.sales.sale-completed.v1`. Declare with `@Externalized("bmp.sales.bill::#{aggregateId()}")` in phase 06 (topic `bmp.sales.bill`, key = aggregate id).
- Document every event in `contracts/asyncapi/events.yaml` with a JSON Schema and an example before implementing it. The list of events is in `docs/reference/event-catalogue.md`.

## 2. Schema evolution

- Additive changes only within a version: new optional fields. Consumers ignore unknown fields.
- Breaking change → new record `SaleCompletedV2` and type `...v2`; publish both until all consumers migrate.
- Never reuse a removed field name with a different meaning.

## 3. Publishing

- Publish from the **application handler**, inside the transaction, via `ApplicationEventPublisher` (or by registering events on the aggregate and publishing them after save — choose one style per codebase in phase 00 and record it in an ADR; recommended: aggregates collect events in a transient list, handler publishes them after `save`).
- Spring Modulith records the publication in `platform.event_publication` in the same transaction → guaranteed delivery once committed (outbox).
- Never publish an event for something that did not commit; never send to external systems directly from a handler.

## 4. Consuming

- Use `@ApplicationModuleListener` (async, runs after commit in its own transaction).
- Each listener class lives in `adapter/in/event`, named `<Event>Listener` or `On<Event>`, and delegates to an application handler.
- **Tenant and trace context:** wrap every listener invocation (an aspect or base helper `EventContext.run(event, () -> ...)`) that sets `TenantContext`, MDC (`tenantId`, `eventId`, `eventType`), `ActorContext` from metadata, and restores the trace parent. Clear everything in `finally`.
- **Idempotency (inbox):** before handling, insert `(consumer_name, event_id)` into `platform.inbox` in the listener's transaction (`INSERT ... ON CONFLICT DO NOTHING`); if no row was inserted, the event was already processed → return. Because the inbox insert and the business change share a transaction, the effect is exactly-once.
- **Ordering:** if a listener depends on order per aggregate (e.g. bill completed before bill voided), check `aggregateVersion` against what it has stored; if an earlier version is missing, throw a retryable `OutOfOrderEventException` so it is retried later.
- Listeners must not call back synchronously into the publisher module to fetch data that should have been in the event. If data is missing, extend the event.

## 5. Failure handling

- See `04-error-handling.md` §7. Summary: retry transient failures with back-off (resubmission job over incomplete publications), park non-retryable failures in `platform.failed_event` with the Problem JSON and alert, provide an admin endpoint to inspect and replay failed events (`GET/POST /api/v1/admin/failed-events`).

## 6. Choreography vs orchestration

- Default: **choreography**. Each module reacts to events and publishes its own outcome (bill completion flow in `docs/reference/event-catalogue.md`).
- Multi-step processes with compensations (returns with refunds, period close, tenant onboarding) are coordinated by a **process manager** in the owning module (a persistent state machine table + listeners) during phases 03–05, and migrated to **Temporal** workflows in phase 06.

## 7. Testing events

- Module tests with `@ApplicationModuleTest` and the `Scenario` API:

```java
@ApplicationModuleTest
class GoodsReceiptPostingTests {
    @Test
    void postingReceiptPublishesGoodsReceived(Scenario scenario) {
        scenario.stimulate(() -> handler.handle(command))
                .andWaitForEventOfType(GoodsReceived.class)
                .matching(e -> e.lines().size() == 3)
                .toArriveAndVerify(e -> assertThat(e.supplierId()).isEqualTo(SUPPLIER));
    }
}
```

- For every listener: test idempotency (deliver the same event twice → one effect), tenant context (effect lands in the event's tenant), and out-of-order handling where relevant.
- An `EventContractTest` serializes every event record to JSON and validates it against the AsyncAPI schema.
