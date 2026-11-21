# 02 — Coding standards (Java 25 / Spring Boot 4)

## 1. General principles

- Optimise for the reader. Code is read far more than written; a newcomer should understand a class in one pass.
- Small, cohesive classes (guideline: < 200 lines) and methods (guideline: < 25 lines). Split when a method needs a comment to explain its sections.
- SOLID, but no speculative abstraction: create an interface when there are two implementations or a module boundary/port, not "just in case".
- Fail fast: validate at the edge, enforce invariants in the domain, never let invalid state exist.
- Immutability by default: records for DTOs, commands, events, value objects; `final` fields; `List.copyOf` in compact constructors.
- No magic numbers or strings: constants, enums or configuration.

## 2. Formatting and tooling (enforced by the build)

- **Spotless** with palantir-java-format; run `./gradlew spotlessApply` before committing.
- **Error Prone** with warnings as errors; **NullAway** in `JSpecify` mode for `com.bmp` packages.
- Every package has `package-info.java` with `@NullMarked`. Mark nullable things explicitly with `@Nullable`. Never return `null` collections; return empty.
- Compiler flags: `-Xlint:all -Werror -parameters`.
- Imports: no wildcards, no unused imports.

## 3. Naming

| Element | Convention | Example |
| --- | --- | --- |
| Packages | lowercase, singular | `com.bmp.inventory.domain.model` |
| Classes | nouns, PascalCase | `StockMovement`, `PriceResolver` |
| Handlers | `<Verb><Noun>Handler` | `CompleteBillHandler` |
| Events | past tense, `<Noun><Verb-ed>` | `SaleCompleted`, `StockIssued` |
| Booleans | `is/has/can` | `isSellable()`, `hasBatches()` |
| Tests | `<ClassUnderTest>Test`, `<Feature>IT` for integration | `VariantMatrixGeneratorTest`, `GoodsReceiptApiIT` |
| Test methods | `should<Outcome>When<Condition>` or readable `snake_case` sentences | `shouldRejectReceiptWhenUnitNotBuyable` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_BATCH_SIZE` |
| DB | `snake_case` tables (singular) and columns | `stock_movement.qty_base` |
| JSON | `camelCase` fields, `UPPER_SNAKE_CASE` enums | `"stockState": "OPEN"` |

Use the domain language from `docs/reference/domain-model.md` exactly (Variant, PackagingUnit, Transformation, Bill, CreditNote...). Do not invent synonyms.

## 4. Modern Java usage

- **Records** for value objects, DTOs, commands, events, read models.
- **Sealed interfaces** for closed hierarchies (e.g. `sealed interface Tender permits CashTender, CardTender, CreditTender, TransferTender`) and exhaustive `switch` with pattern matching, no `default` branch when the hierarchy is sealed.
- **Virtual threads** are on; do not create thread pools for request handling. Avoid `synchronized` around blocking I/O (use `ReentrantLock` if a lock is truly needed).
- `Optional` only as a return type for "may be absent" lookups; never as a field or parameter.
- Streams for transformations; plain loops when clearer or when exceptions/side effects are involved.
- `var` only when the type is obvious from the right-hand side.
- Text blocks for SQL in `JdbcClient` code.
- Time: inject `java.time.Clock`; never call `Instant.now()`/`LocalDate.now()` directly. Store `Instant` (UTC). Convert to business dates with the tenant's `ZoneId`.

## 5. Money, quantities and rounding

- `Money(BigDecimal amount, CurrencyCode currency)`: arithmetic methods (`plus`, `minus`, `times(Quantity|BigDecimal)`, `allocate(List<BigDecimal> weights)`), currency mismatch throws.
- Storage scale: money `NUMERIC(19,4)`, unit costs `NUMERIC(19,6)`, quantities `NUMERIC(20,6)`.
- **Rounding rule:** compute at full precision; round **at line level** to the currency's minor unit (LKR = 2) with `RoundingMode.HALF_UP` (configurable per tenant via `PriceRoundingPolicy`); bill totals are sums of rounded lines; tax is rounded per line unless the tenant setting says per bill.
- **Allocation** (bill discount across lines, cost across transformation outputs) uses the largest-remainder method so parts always sum exactly to the whole.
- Never compare `BigDecimal` with `equals` (scale-sensitive); use `compareTo` or the value object's `isEqualTo`.
- `Quantity(BigDecimal value, UnitCode unit)`; conversion to base units goes through `PackagingUnit.toBase(qty)` and product conversions only.
- Serialization: money amounts and quantities as **JSON strings** (`"1250.00"`), never JSON numbers.

## 6. Spring usage

- Constructor injection only; single constructor needs no `@Autowired`.
- Beans package-private where possible; only `api` types and controllers' generated interfaces are public.
- `@Transactional` only on application-layer handlers (and ledger writers called by them). Read-only queries: `@Transactional(readOnly = true)`.
- No `@Transactional` on private methods or on controllers.
- Use `@ApplicationModuleListener` (async, transactional, after commit) for cross-module event listeners.
- HTTP clients (phase 06): declarative `@HttpExchange` interfaces with timeouts set.
- No `RestTemplate`. No field injection. No static access to the `ApplicationContext`.

## 7. Comments and documentation

- Javadoc on every public type in `api` packages and every aggregate's public method, explaining **why** and the invariants, not restating the code.
- Inline comments only for non-obvious reasons (a business rule reference, a workaround with a link).
- Reference business rules by their id from the phase file, e.g. `// Rule INV-07: sales never blocked by negative stock`.
- Each module has a `README.md`: purpose, aggregates, events in/out, endpoints, settings.

## 8. Dependencies

- Add libraries only through `gradle/libs.versions.toml`. Prefer what Spring Boot manages. Each new library needs a one-line justification in the commit message.
- No Lombok, no Guava (JDK covers it), no Apache Commons Lang unless a specific need is justified.

## 9. Performance hygiene

- No N+1: use fetch joins or projections; assert query counts in integration tests for list endpoints (`datasource-proxy` or Hibernate statistics).
- Paginate every list endpoint (cursor-based); max page size 200.
- Batch inserts for ledgers (`JdbcClient` batch / `jdbc.batch_size`).
- Index every foreign-key-like column and every column used in filters; first index column is `tenant_id`.
- Cache only immutable or versioned data (catalog snapshots, price list versions) and always key caches by tenant.
