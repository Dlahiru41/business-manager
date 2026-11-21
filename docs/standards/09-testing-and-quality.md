# 09 — Testing and quality gates

## 1. Test pyramid

| Level | Scope | Tools | Location / naming | Speed target |
| --- | --- | --- | --- | --- |
| Unit | Domain model, value objects, policies, mappers, pure application logic with fakes | JUnit Jupiter, AssertJ, (Mockito sparingly) | `src/test/java`, `*Test` | whole module < 10 s |
| Module integration | One module with real PostgreSQL, its migrations, its events | `@ApplicationModuleTest`, Testcontainers, `Scenario` | `src/integrationTest/java`, `*IT` | < 2 min per module |
| API | HTTP endpoints, security, Problem Details, contract conformance | `MockMvcTester` (or REST Assured on a random port), `swagger-request-validator`, JWT test helper | `*ApiIT` | |
| End-to-end flow | Cross-module business flows in the full app (e.g. receive → sell → ledger) | `@SpringBootTest`, Testcontainers (Postgres, Keycloak) | `app/src/integrationTest`, `*FlowIT` | < 5 min total |
| Architecture | Module boundaries, layering, conventions | Spring Modulith `verify()`, ArchUnit | `ModularityTests`, `ArchitectureTest` | |
| Mutation | Domain packages | PIT | `./gradlew pitest` | phase gate from 02 |

Gradle: a separate `integrationTest` source set and task (JVM Test Suite plugin) wired into `check`.

## 2. What to test

- **Every business rule** in a phase file (rule ids like `CAT-03`, `INV-07`) has at least one test whose name or `@DisplayName` contains the rule id.
- **Every error code** has a test (see `04-error-handling.md` §9).
- **Every endpoint**: happy path, validation failure, 401, 403, 404 cross-tenant, idempotent replay (POST actions), `412` on stale `If-Match` (updates), response validates against OpenAPI.
- **Every listener**: effect, idempotent re-delivery, tenant context.
- **Money and quantities**: rounding edge cases (x.xx5), allocation sums, zero and negative where meaningful, large values, unit conversions both ways.
- **Concurrency** where it matters: two concurrent issues on the same stock balance produce the correct final balance (use `ExecutorService` with virtual threads in an IT).

## 3. Test style

- Arrange / Act / Assert, one behaviour per test, descriptive names.
- AssertJ only (no Hamcrest / JUnit asserts). Use `assertThat(money).isEqualByComparingTo(...)` for `BigDecimal`.
- Test data builders in `src/testFixtures` (Gradle `java-test-fixtures`): `aVariant().withSku("EGG-RED-L").build()`. Share fixtures across modules through the module's `testFixtures` (only `api` types).
- No `Thread.sleep`; use Awaitility or the Modulith `Scenario` API for async.
- Fixed `Clock` in tests (`MutableClock` in test-support).
- Prefer in-memory fakes of ports over Mockito mocks for domain/application tests; mock only external gateways.
- Tests must be independent and parallel-safe: each test uses its own tenant id (random) instead of cleaning tables.

## 4. Reference scenario fixtures (the pilot shop)

`libs/test-support` provides `PilotShopFixture` that, through the **public APIs**, creates a tenant with:
- Eggs: 3 colours × 5 sizes minus Golden Yellow {XS, S, M} = 12 variants, base unit piece, optional tray of 30.
- Cooking oil: 1 variant, states SEALED/OPEN, base unit ml, can = 19,565 ml (configurable), bottles 1000/750/375/187.5 ml, `BREAK_ON_SALE`.
- Soya meat: 2 brands × 2 packet sizes (branded) + loose soya (g, own 250 g / 500 g packets).
- Dried fish (Karola): loose (g) + own 250 g / 500 g packets.
- Price lists Retail and Wholesale with prices for each sellable unit.

End-to-end flow tests use this fixture so the system is always proven against the real customer's shape.

## 5. Quality gates (fail the build)

| Gate | Threshold |
| --- | --- |
| Compilation | `-Werror`, Error Prone, NullAway clean |
| Formatting | Spotless check |
| Tests | 100% passing; no `@Disabled` without an issue link and expiry date |
| Coverage (JaCoCo) | ≥ 85% line / ≥ 80% branch on `domain` and `application` packages; ≥ 70% overall per module |
| Mutation (PIT, from phase 02) | ≥ 70% mutation score on `domain` packages |
| Architecture | Spring Modulith `verify()`, ArchUnit rules (layering, no field injection, controllers have `@PreAuthorize`, no `java.util.Date`, no `double` in domain, no `System.out`, no cross-module internal imports, no logging of `@Sensitive` types) |
| Contracts | Spectral lint clean; OpenAPI response validation in API tests; no breaking diff vs `main` without a new version |
| Security | Dependency scan: no unsuppressed CVSS ≥ 7 |
| Migrations | `RlsCoverageIT` green; Flyway validate |

## 6. CI pipeline (GitHub Actions)

1. Checkout, set up Java 25 (Temurin), Gradle cache.
2. `./gradlew spotlessCheck build` (unit + integration tests with Testcontainers on the runner's Docker).
3. Contract lint + breaking-change check.
4. Dependency scan + SBOM.
5. On `main`: build image (`bootBuildImage`), sign, push; deploy to staging (phase 06 via Argo CD).
6. Publish test reports, coverage, PIT report and Spring Modulith documentation as artifacts.

## 7. Definition of Done (applies to every task)

- Contract, migration, code, tests and module README updated together.
- All gates green locally and in CI.
- `docs/PROGRESS.md` updated.
- No new TODO/FIXME without a PROGRESS entry.
- Manual smoke through the `.http` file for new endpoints.
