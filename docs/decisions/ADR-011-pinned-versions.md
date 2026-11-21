# ADR-011: Pinned dependency and tool versions

- Status: accepted
- Date: 2026-11-21
- Phase / task: 00 / T00-01

## Context
CLAUDE.md §2 requires every library and tool to be pinned to the latest **stable** patch of its line at the start of
Phase 00, with no milestones or release candidates. Spring Boot 4.2.0 went GA on 2026-11-19, so per CLAUDE.md the 4.2
line is used instead of 4.1. Versions were read from Maven Central and the Gradle Plugin Portal metadata on 2026-11-21.

## Options considered
1. Pin exact versions in `gradle/libs.versions.toml`, letting the Spring Boot BOM manage everything it covers. Pros:
   one place, reproducible builds, Boot-tested combinations. Cons: manual upgrades (mitigated by Renovate/Dependabot).
2. Dynamic versions (`4.2.+`). Rejected: non-reproducible builds, silent behaviour changes.

## Decision
Option 1. Versions chosen:

| Concern | Artifact | Version | Note |
| --- | --- | --- | --- |
| Build | Gradle (wrapper, SHA-256 pinned) | 9.9.2 | daemon on Java 25 via `gradle/gradle-daemon-jvm.properties` |
| Language | Java toolchain | 25 (Temurin 25.0.4 locally) | LTS |
| Framework | Spring Boot (BOM + plugin) | 4.2.0 | Framework 7.1.0, Security 7.2.0, Hibernate 7.4.15, Jackson 3.1.8, Flyway 12.11.0, Testcontainers 2.0.5, JUnit 6.1.3, Micrometer 1.18.0, Micrometer Tracing 1.8.0, OpenTelemetry 1.68.0, PostgreSQL JDBC 42.7.14 (all BOM-managed) |
| Modularity | Spring Modulith BOM | 2.2.0 | matches Boot 4.2 |
| Later (phase 06) | Spring Cloud BOM | 2026.0.0 | latest GA; Boot 4.2 compatibility must be re-checked in phase 06 |
| API docs UI | springdoc-openapi | 3.2.1 | built against Boot 4.2.0 |
| Codegen | openapi-generator Gradle plugin | 7.27.0 | `useSpringBoot4`, `useJackson3` |
| Mapping | MapStruct | 1.6.3 | 1.7.0 is still beta |
| Ids | java-uuid-generator | 5.2.0 | UUIDv7 |
| Scheduling | ShedLock | 7.11.0 | |
| Resilience | Resilience4j BOM | 2.4.0 | core modules only (circuit breaker) |
| JDBC spans | datasource-micrometer-spring-boot | 2.3.1 | |
| Settings schemas | networknt json-schema-validator | 2.0.8 | Jackson 2 API; same major as the contract validator (openapi-request-validator 3.0.0 needs 2.x), so both share one version |
| IdP | Keycloak server image | 26.8.2 | `quay.io/keycloak/keycloak` |
| Test | Testcontainers Keycloak | 4.4.0 | Testcontainers 2.x line |
| Test | ArchUnit | 1.5.1 | |
| Test | swagger-request-validator | 3.0.0 | |
| Null safety | JSpecify | 1.0.1 | |
| Static analysis | Error Prone / NullAway / Gradle plugin | 2.53.0 / 0.14.4 / 5.2.0 | |
| Formatting | Spotless plugin / palantir-java-format | 8.12.0 / 2.103.0 | |
| Coverage | JaCoCo | 0.8.16 | |
| Mutation | PIT / junit5 plugin / Gradle plugin | 1.30.2 / 1.2.3 / 1.19.0 | |
| SBOM | CycloneDX Gradle plugin | 3.6.1 | |
| Vulnerabilities | OWASP Dependency-Check Gradle plugin | 13.0.0 | Trivy is the blocking CI scan |
| Bootstrapping | foojay toolchain resolver | 1.0.0 | |

## Consequences
- Upgrades are a one-line change in the catalog plus a build run; Spring Boot patch updates are applied monthly
  (08-security.md §9).
- Spring Boot 4.2.0 is two days old. If a third-party library turns out to be incompatible, the fallback is the 4.1.x
  line (4.1.3) with Modulith 2.1.3. That change needs a superseding ADR.
- The PIT Gradle plugin uses a Gradle API that is deprecated for Gradle 11. Gradle therefore runs in its default
  warning mode (summary), not `fail`. Revisit when the plugin is updated.
