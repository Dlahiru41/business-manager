# CLAUDE.md — Business Management Platform (BMP)

You are building the backend of a multi-tenant **inventory, billing and finance platform** for retail and wholesale businesses. The first customer is a shop selling eggs, cooking oil, soya meat and dried fish, but **nothing may be hard-coded for those products**: every product rule (variants, units, conversions, prices) is data configured per tenant.

This file is the entry point. Read it fully at the start of every session.

---

## 1. How to work in this repository

### 1.1 Reading order (every session)

1. `CLAUDE.md` (this file)
2. `docs/PROGRESS.md` — what is done, what is in progress, open questions
3. The current phase file in `docs/phases/`
4. The standards files the phase lists under **Read before starting** (all of `docs/standards/` on the first session)
5. The reference files the phase lists (`docs/reference/`)

Do not read every file every time; read what the phase tells you to, and re-read a standard when you are about to do work it governs (e.g. read `04-error-handling.md` before writing an exception handler).

### 1.2 How to execute a phase

The user starts a phase with `/run-phase 00` (see `.claude/commands/run-phase.md`) or by asking you to "execute phase NN".

For each phase:

1. **Plan.** Read the phase file. Create a todo list with one item per task in the phase's **Tasks** section, in order. Do not reorder tasks unless a dependency forces it; if so, note why in `docs/PROGRESS.md`.
2. **For each task:**
   1. Re-read the task's acceptance criteria.
   2. Write or update the **contract first** (OpenAPI in `contracts/openapi/`, AsyncAPI in `contracts/asyncapi/`, Flyway migration) before implementation code.
   3. Write tests first for domain logic (TDD). For adapters, write the test alongside.
   4. Implement following `docs/standards/`.
   5. Run `./gradlew spotlessApply build` — it must pass with **zero** warnings-as-errors failures, zero test failures, and the architecture tests green.
   6. Self-review against the checklist in §5 below.
   7. Commit (see §4). One task can be several commits; never leave the build red between commits.
   8. Tick the task in `docs/PROGRESS.md`.
3. **Close the phase.** Run the phase's **Definition of Done** checks, then run the `code-reviewer` subagent (`.claude/agents/code-reviewer.md`) over the phase's changes, fix what it finds, and write the phase summary in `docs/PROGRESS.md`.

### 1.3 When something is unclear

1. Look in `docs/reference/` (domain model, data model, events, posting rules).
2. If still unclear, choose the **most conservative option that keeps data correct and auditable**, record it as an ADR in `docs/decisions/ADR-NNN-title.md` (template in `docs/decisions/README.md`), and add a line under **Open questions** in `docs/PROGRESS.md`.
3. Never invent business rules silently. Never block on a question you can record and work around.

### 1.4 Hard rules (never break these)

- Never weaken, skip, `@Disabled`, or delete a test to make the build pass. Fix the code or, if the test is wrong, fix the test and explain why in the commit message.
- Never disable a quality gate (Spotless, Error Prone, NullAway, ArchUnit, Modulith verification, JaCoCo thresholds).
- Never access another module's internal packages, tables or repositories. Cross-module access is only through the other module's `api` package or its events.
- Never update or delete rows in ledger tables (`stock_movement`, `payment`, `refund`, `journal_entry`, `journal_line`, `audit_record`). Corrections are new reversing records.
- Never use `double`/`float` for money or quantities. Use `BigDecimal` through the `Money` and `Quantity` value objects.
- Never log secrets, tokens, passwords or full personal data (see `05-logging-and-observability.md`).
- Never trust a tenant id from the request body or URL. The tenant comes from the authenticated principal.
- Never commit secrets. Local secrets live in `.env` (git-ignored) and `deploy/local/` defaults only.
- Never change a published API or event contract in a breaking way (see `03-api-standards.md` §Versioning).
- Never use field injection (`@Autowired` on fields). Constructor injection only.

---

## 2. Tech stack (all Java ecosystem)

Pin exact versions in `gradle/libs.versions.toml` during Phase 00. At that moment, check Maven Central / spring.io for the **latest stable patch** of each line below and pin it. Do not use milestones or release candidates.

| Concern | Choice |
| --- | --- |
| Language | **Java 25 (LTS)** via Gradle toolchains. Use records, sealed interfaces, pattern matching, `switch` expressions, text blocks, virtual threads. No preview features. |
| Framework | **Spring Boot 4.1.x** (Spring Framework 7, Jakarta EE 11). If 4.2.x is GA when Phase 00 starts, use 4.2.x. |
| Modularity | **Spring Modulith 2.x** (matching the Boot line): module boundaries, verification, event publication registry (outbox), documentation, module tests. |
| Build | **Gradle 9 (Kotlin DSL)**, version catalog, convention plugins in `build-logic/`. |
| Web | Spring MVC on virtual threads (`spring.threads.virtual.enabled=true`), Jakarta Bean Validation, `ProblemDetail` (RFC 9457). |
| JSON | Jackson 3 (Boot 4 default, package `tools.jackson`). |
| API contracts | OpenAPI 3.1 files in `contracts/openapi/`, code generated with **openapi-generator-gradle-plugin** (`spring` generator, interfaces + DTOs only). Springdoc for runtime docs UI. AsyncAPI 3.0 for events. |
| Persistence | **PostgreSQL 18**, Spring Data JPA (Hibernate 7) for aggregates, `JdbcClient` for ledgers and read queries, **Flyway** for migrations. |
| Multi-tenancy | Hibernate `@TenantId` discriminator + PostgreSQL Row-Level Security (defence in depth). |
| Events (phase 00–05) | Spring application events + Spring Modulith JDBC **event publication registry** (transactional outbox). |
| Events (phase 06) | Apache Kafka 4.x via Spring Modulith event externalization (`spring-modulith-events-kafka`), CloudEvents envelope. |
| Workflows (phase 06) | Temporal Java SDK. |
| Security | Spring Security 7 OAuth2 Resource Server (JWT), **Keycloak 26.x** as IdP, method security, custom `PermissionEvaluator`. |
| Resilience | Spring Framework 7 built-in resilience (`@Retryable`, `@ConcurrencyLimit`) and **Resilience4j** for circuit breakers on outbound calls. |
| Caching | Spring Cache with Caffeine (in-process); Redis via Spring Data Redis when scaled out (phase 06). |
| Scheduling | `@Scheduled` + **ShedLock** (JDBC) so jobs run once across instances. |
| Observability | Spring Boot Actuator, **Micrometer** metrics (Prometheus registry), **Micrometer Tracing + OpenTelemetry** (OTLP exporter), Boot **structured logging** (ECS JSON) on SLF4J/Logback. |
| Mapping | **MapStruct** for DTO ↔ domain mapping. No Lombok. |
| IDs | UUIDv7 via `com.fasterxml.uuid:java-uuid-generator`. |
| Null safety | **JSpecify** annotations (`@NullMarked` per package) + **NullAway** on Error Prone. |
| Code quality | **Spotless** (palantir-java-format), **Error Prone**, **NullAway**, **ArchUnit**, **JaCoCo**, **OWASP Dependency-Check** (or Trivy in CI). |
| Testing | JUnit Jupiter, AssertJ, Mockito, **Testcontainers 2.x** (PostgreSQL, Keycloak, Kafka later), Spring `MockMvcTester`, Spring Modulith `@ApplicationModuleTest` + `Scenario`, **swagger-request-validator** (contract conformance), **PIT** mutation testing on domain packages (phase gate from phase 02). |
| Containers | Spring Boot `bootBuildImage` (Paketo buildpacks) or **Jib**; Docker Compose for local; Helm charts in phase 06. |
| Local infra | `deploy/local/compose.yaml`: PostgreSQL 18, Keycloak, Mailpit, `grafana/otel-lgtm` (Grafana + Tempo + Loki + Prometheus in one container). |
| CI | GitHub Actions: build, test, quality gates, image build, SBOM (CycloneDX Gradle plugin). |
| Print agent (phase 03) | Small Java 25 Spring Boot app using an ESC/POS library (e.g. `escpos-coffee`) running on the shop PC. |
| API gateway (phase 06) | Spring Cloud Gateway. |

**Out of scope for these instructions:** the POS front-end, back-office web UI and mobile app. You build the APIs, services, print agent and everything they need. Provide `http/` request files (IntelliJ HTTP client format) per module so the APIs can be exercised without a UI.

---

## 3. Repository layout

```
bmp-platform/
├── CLAUDE.md
├── .claude/
│   ├── commands/run-phase.md
│   └── agents/code-reviewer.md
├── docs/
│   ├── PROGRESS.md
│   ├── standards/          # how to write code (binding)
│   ├── reference/          # what the domain is (binding)
│   ├── phases/             # what to build, in order
│   └── decisions/          # ADRs you write
├── build-logic/            # Gradle convention plugins (java-conventions, spring-module, quality)
├── gradle/libs.versions.toml
├── contracts/
│   ├── openapi/            # one file per module: catalog.yaml, pricing.yaml, ...
│   └── asyncapi/           # events.yaml
├── libs/
│   ├── shared-kernel/      # Money, Quantity, ids, TenantId, Clock, base exceptions — NO Spring web/JPA deps
│   ├── web-support/        # problem handling, request-id, idempotency, ETag, tenant resolution
│   ├── persistence-support/# tenant RLS wiring, auditing, JdbcClient helpers, outbox/inbox helpers
│   └── test-support/       # Testcontainers config, fixtures, JWT helpers, contract validator
├── modules/                # one Gradle subproject per bounded context (Spring Modulith module)
│   ├── iam/  tenant/  catalog/  pricing/  inventory/  procurement/  party/
│   ├── sales/  payments/  finance/  reporting/  documents/  sync/  onboarding/  audit/  notification/
├── app/                    # the Spring Boot application that assembles all modules (modular monolith)
├── print-agent/            # phase 03
├── deploy/
│   ├── local/compose.yaml
│   └── helm/               # phase 06
└── http/                   # .http request collections per module
```

Base package: `com.bmp`. Each module's root package is `com.bmp.<module>` (e.g. `com.bmp.inventory`). The internal structure of a module is defined in `docs/standards/01-architecture-and-code-structure.md`.

---

## 4. Commands and git

```bash
./gradlew spotlessApply          # format
./gradlew build                  # compile + unit + integration tests + quality gates
./gradlew test                   # unit tests only
./gradlew integrationTest        # Testcontainers-based tests
./gradlew :app:bootRun           # run locally (needs compose up)
./gradlew pitest                 # mutation tests (domain packages)
docker compose -f deploy/local/compose.yaml up -d
```

- **Conventional Commits:** `feat(catalog): generate variant matrix with exclusions`, `fix(inventory): ...`, `test(...)`, `refactor(...)`, `docs(...)`, `build(...)`, `chore(...)`.
- Work on a branch per phase: `phase/NN-short-name`. Merge to `main` only when the phase's Definition of Done passes.
- End commit messages with the attribution lines your environment requires.

---

## 5. Self-review checklist (before every commit)

- [ ] Contract (OpenAPI / AsyncAPI / migration) updated first and matches the code.
- [ ] Module boundaries respected; `ModularityTests` and ArchUnit pass.
- [ ] Every public endpoint: authorization rule, validation, idempotency (for POST actions), ETag/If-Match (for updates), Problem Details errors, OpenAPI examples.
- [ ] Domain invariants enforced inside the aggregate, not in controllers.
- [ ] Money/quantities use value objects; rounding follows `02-coding-standards.md` §Money.
- [ ] Every state change that other modules care about publishes a domain event; listeners are idempotent.
- [ ] Logging per `05-logging-and-observability.md`: no PII/secrets, correct levels, business events logged once.
- [ ] Exceptions per `04-error-handling.md`: specific error code, no swallowed exceptions, no stack traces to clients.
- [ ] Tests: happy path, each business rule, each error code, tenant isolation, idempotent replay.
- [ ] No TODO without a linked line in `docs/PROGRESS.md`.
- [ ] `./gradlew build` green.
