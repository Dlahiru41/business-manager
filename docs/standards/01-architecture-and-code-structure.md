# 01 — Architecture and code structure

Binding for every module. The goal: each module can be lifted out into its own service in phase 06 by moving a Gradle subproject, swapping in-process calls for HTTP clients and in-process events for Kafka, with **no change to domain code**.

## 1. Modular monolith with Spring Modulith

- One Spring Boot application (`app/`) assembles all modules. Each module is a Gradle subproject under `modules/<name>` and a Spring Modulith **application module** rooted at `com.bmp.<name>`.
- `app/src/test/java/com/bmp/ModularityTests.java` must contain:

```java
class ModularityTests {
    static final ApplicationModules MODULES = ApplicationModules.of(BmpApplication.class);

    @Test void verifiesModuleStructure() { MODULES.verify(); }

    @Test void writesDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml().writeModuleCanvases();
    }
}
```

- `MODULES.verify()` fails the build on dependency cycles or access to another module's internal packages. Never suppress it.
- Allowed dependencies are declared explicitly in each module's `package-info.java`:

```java
@ApplicationModule(displayName = "Inventory", allowedDependencies = { "tenant::api", "catalog::api", "procurement::api", "sales::api" })
@NullMarked
package com.bmp.inventory;
```

## 2. Module dependency rules

Listening to another module's event is a dependency on that module's `api` (the event types live there), so it counts in this table. **The graph must stay acyclic**: Spring Modulith fails the build on cycles.

| Module | May depend on (`api` only; includes consuming its events) |
| --- | --- |
| `tenant` | none |
| `iam` | `tenant` |
| `catalog` | `tenant` |
| `pricing` | `tenant`, `catalog` |
| `party` | `tenant` |
| `procurement` | `tenant`, `catalog`, `party` |
| `sales` | `tenant`, `catalog`, `pricing`, `party` |
| `inventory` | `tenant`, `catalog`, `procurement`, `sales` |
| `payments` | `tenant`, `party`, `sales` |
| `finance` | `tenant`, `party`, `catalog`, `procurement`, `inventory`, `sales`, `payments` |
| `documents` | `tenant`, `sales` |
| `sync` | `tenant`, `iam`, `catalog`, `pricing`, `party`, `inventory`, `sales`, `payments` |
| `reporting` | all business modules above |
| `onboarding` (phase 05) | `tenant`, `iam`, `catalog`, `pricing`, `party`, `finance` |
| `audit`, `notification` | any module (they only consume events) |

Upstream → downstream order: `tenant → iam/catalog/party → pricing/procurement → sales → inventory/payments → finance → reporting`. Nothing may point back up this chain.

**When an upstream module needs data or behaviour that only a downstream module has, use an SPI (dependency inversion), never a reverse dependency:**
- The upstream module declares an interface in `com.bmp.<upstream>.api.spi` (e.g. `sales.api.spi.CreditExposureProvider`, `procurement.api.spi.ReorderSuggestionProvider`).
- The downstream module implements it as a Spring bean (finance implements `CreditExposureProvider`; inventory implements `ReorderSuggestionProvider`).
- The upstream module ships a conservative default implementation annotated `@ConditionalOnMissingBean`, so it works before the downstream module exists.

Every module may depend on `libs/shared-kernel`, `libs/web-support`, `libs/persistence-support`. Anything not in this table needs an ADR. Prefer **events** over synchronous `api` calls whenever the caller does not need an immediate answer.

## 3. Package structure inside a module (hexagonal)

```
com.bmp.inventory
├── package-info.java                 # @ApplicationModule, @NullMarked
├── api/                              # PUBLIC: the only package other modules may import
│   ├── package-info.java             # @NamedInterface("api")
│   ├── InventoryQueries.java         # interface other modules call (read-only where possible)
│   ├── dto/                          # immutable records exchanged with other modules
│   ├── events/                       # published domain events (records)
│   └── spi/                          # interfaces downstream modules implement (see §2)
├── domain/                           # PURE: no Spring, no JPA annotations except mapping (see §5)
│   ├── model/                        # aggregates, entities, value objects
│   ├── service/                      # domain services (stateless rules spanning aggregates)
│   ├── policy/                       # strategy interfaces, e.g. CostingPolicy, NegativeStockPolicy
│   ├── port/                         # repository and gateway interfaces (outbound ports)
│   └── error/                        # module ErrorCode enum + domain exceptions
├── application/                      # USE CASES: transactions, orchestration, authorization
│   ├── command/                      # command records + handlers (one handler per use case)
│   ├── query/                        # query services returning read models
│   └── InventoryQueriesImpl.java     # implements api.InventoryQueries
├── adapter/
│   ├── in/web/                       # controllers implementing generated OpenAPI interfaces + MapStruct mappers
│   ├── in/event/                     # @ApplicationModuleListener classes consuming other modules' events
│   ├── in/job/                       # @Scheduled jobs (with ShedLock)
│   └── out/persistence/              # JPA repositories, JdbcClient ledger writers, projections
└── config/                           # @Configuration, @ConfigurationProperties records
```

Rules:
- **Dependency direction:** `adapter → application → domain`. `domain` depends on nothing but `shared-kernel` and the JDK. Enforce with ArchUnit (`libs/test-support` provides `HexagonalRules`).
- **Controllers are thin:** map request → command, call one handler, map result → response. No business logic, no repository calls, no `@Transactional`.
- **Application handlers own transactions** (`@Transactional` on the handler method), load aggregates through ports, call domain methods, save, and publish events.
- **Aggregates enforce invariants.** Setters do not exist; state changes go through intention-revealing methods (`variant.deactivate(reason)`, `bill.complete(clock)`).
- **One aggregate per transaction** where possible. When a use case must change two aggregates in the same module, do it in one handler; across modules, use events.
- Only `api` types cross module boundaries. Never expose entities or JPA types through `api`.

## 4. Commands, queries and handlers

```java
public record ReceiveGoodsCommand(GoodsReceiptId id, SupplierId supplierId, LocationId locationId,
                                  Instant receivedAt, List<Line> lines) {
    public ReceiveGoodsCommand { lines = List.copyOf(lines); }
    public record Line(VariantId variantId, PackagingUnitId unitId, Quantity qty, Money unitCost,
                       @Nullable String batchCode, @Nullable LocalDate expiresOn) {}
}

@Service
class ReceiveGoodsHandler {
    private final GoodsReceiptRepository receipts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ReceiveGoodsHandler(GoodsReceiptRepository receipts, ApplicationEventPublisher events, Clock clock) { ... }

    @Transactional
    @PreAuthorize("hasPermission(null, 'procurement:receive')")
    public GoodsReceiptView handle(ReceiveGoodsCommand cmd) { ... }
}
```

- Commands and queries are immutable records validated with Bean Validation at the web edge and re-checked by the domain.
- One handler class per use case. Name: `<Verb><Noun>Handler`.
- Query services return **read models** (records), never aggregates.

## 5. Persistence mapping decision

Pragmatic DDD: aggregates are JPA entities **in the domain package**, using only `jakarta.persistence` mapping annotations (no Spring, no repositories inside entities). This avoids a duplicate model. Rules:
- Field access, `protected` no-arg constructor for JPA, all other constructors/factories enforce invariants.
- Value objects are `@Embeddable` records or mapped with `AttributeConverter`.
- No bidirectional associations unless required; no `EAGER` fetching; no cascade across aggregate boundaries.
- Ledgers (`stock_movement`, `journal_*`, `payment`) are **not** JPA entities: write them with `JdbcClient` from `adapter/out/persistence` for append-only, batch-friendly inserts.

## 6. Shared kernel (`libs/shared-kernel`)

Contains only stable, dependency-free building blocks:
- `Money`, `CurrencyCode`, `Quantity`, `UnitCode`, `Percentage`, `Rounding` policies.
- Typed ids as records wrapping `UUID` (`TenantId`, `ProductId`, `VariantId`, ...) with `IdGenerator` (UUIDv7).
- `TenantContext` (see `08-security.md`), `ActorContext` (user, device).
- Base exception types and `ErrorCode` interface (see `04-error-handling.md`).
- `DomainEvent` marker interface with `eventId`, `tenantId`, `occurredAt`, `aggregateId`, `aggregateVersion`.
- `BusinessDate` helper (tenant time zone + cut-off).

It must not depend on Spring web, JPA, Jackson or any module.

## 7. Configuration

- Use `@ConfigurationProperties` **records** with `@Validated`, prefixed `bmp.<module>.*`. No `@Value` scattered in code.
- Defaults in `application.yaml`; environment overrides through env vars. Profiles: `local`, `test`, `prod` only.
- Per-tenant business settings (negative stock policy, rounding, return window) are **data** in the `tenant` module, not Spring properties.

## 8. Extension points (for business-specific behaviour)

When a business needs special behaviour, add a **policy interface** in the module's `domain/policy` with a default implementation chosen by a tenant setting. Example: `CostingPolicy` (`MOVING_AVERAGE`, `FIFO`), `NegativeStockPolicy` (`ALLOW_AND_FLAG`, `BLOCK`), `PriceRoundingPolicy`. Never add `if (tenant == X)` branches.
