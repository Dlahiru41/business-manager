# Business Management Platform (BMP)

Backend of a multi-tenant inventory, billing and finance platform for retail and wholesale shops: a Spring Boot 4
modular monolith (Spring Modulith) on Java 25 and PostgreSQL 18, with Keycloak for identity. Every product rule is
data configured per tenant.

Start with [CLAUDE.md](CLAUDE.md) (how the code is built), [docs/PROGRESS.md](docs/PROGRESS.md) (where it stands) and
the module READMEs: [tenant](modules/tenant/README.md), [iam](modules/iam/README.md), [audit](modules/audit/README.md).

## Prerequisites

- Docker (Docker Desktop on Windows/macOS) for the local stack and the Testcontainers-based tests.
- A JDK 17+ to launch Gradle. The build itself runs on Java 25, downloaded automatically through Gradle toolchains.
- Optional: IntelliJ IDEA for the `http/*.http` request files.

## Run locally

```bash
docker compose -f deploy/local/compose.yaml up -d   # PostgreSQL, Keycloak, Mailpit, Grafana LGTM
./gradlew :app:bootRun                             # profile "local" by default
```

On start the `local` profile migrates the database and seeds two tenants with one user per role, matching the users
of [deploy/local/keycloak/realm-bmp.json](deploy/local/keycloak/realm-bmp.json):

| Tenant | Id | Users (password `Dev-Passw0rd!`) |
| --- | --- | --- |
| Pilot shop | `01934f5a-6d1c-7000-8000-000000000a01` | `pilot-owner`, `pilot-manager`, `pilot-cashier`, `pilot-storekeeper`, `pilot-accountant` |
| Second shop | `01934f5a-6d1c-7000-8000-000000000a02` | `second-owner`, ..., `second-accountant` |
| (platform) | | `platform-admin` |

| Service | URL |
| --- | --- |
| API | http://localhost:8080 (`/api/v1/...`) |
| Actuator (management port) | http://localhost:8081/actuator/health |
| Keycloak | http://localhost:8180 (admin console: `admin` / `admin`) |
| Mailpit (mails sent by Keycloak) | http://localhost:8025 |
| Grafana (traces, logs, metrics; dashboard "BMP overview") | http://localhost:3000 (`admin` / `admin`) |
| PostgreSQL | `localhost:5432`, database `bmp`, app role `bmp_app` / `bmp_app` |

Override any default by copying `deploy/local/.env.example` to `deploy/local/.env` (git-ignored). Never reuse local
values elsewhere: outside `local`, every secret comes from the environment and the app refuses to start without it.

## Try the API

Open `http/` in IntelliJ, copy `http/http-client.private.env.json.example` to `http/http-client.private.env.json`,
choose the `local` environment, run [http/auth.http](http/auth.http) once for tokens, then
[http/tenant.http](http/tenant.http), [http/iam.http](http/iam.http) and [http/audit.http](http/audit.http).

With curl:

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=bmp-dev-cli -d username=pilot-owner -d 'password=Dev-Passw0rd!' \
  http://localhost:8180/realms/bmp/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/tenant
```

Every POST action needs an `Idempotency-Key` (UUID), every update an `If-Match` with the last ETag. Errors are
Problem Details (RFC 9457) with a stable `code`.

## Build and test

```bash
./gradlew spotlessApply        # format (palantir-java-format)
./gradlew build                # compile, unit + integration tests, Error Prone/NullAway, ArchUnit, Modulith, JaCoCo
./gradlew test                 # unit tests only
./gradlew integrationTest      # Testcontainers tests (PostgreSQL 18, Keycloak 26.8)
./gradlew pitest               # mutation tests of the domain packages
```

Module documentation (PlantUML and canvases) is written to `app/build/spring-modulith-docs` by `ModularityTests`.
Contracts live in [contracts/](contracts/) (OpenAPI 3.1 per module, AsyncAPI 3.0 for events); controller interfaces
and DTOs are generated from them.

## Troubleshooting

- **Port already in use** (5432, 8180, 3000, 8025, 4317/4318): stop the other service or change the left side of the
  port mapping in `deploy/local/compose.yaml`.
- **`password authentication failed for user "bmp_migrator"`**: another PostgreSQL (often one installed as a Windows
  service) answers on port 5432. Start the stack with `BMP_POSTGRES_PORT=55432` and run the app with
  `BMP_DB_URL=jdbc:postgresql://localhost:55432/bmp`.
- **Changed the realm file or the database init script**: both are applied only to an empty stack. Reset it with
  `docker compose -f deploy/local/compose.yaml down -v` and start again.
- **401 from the API**: the token is older than 15 minutes, or was issued by another host name than
  `http://localhost:8180` (the issuer must match `bmp.security.issuer-uri`).
- **403 `TENANT_NOT_ACTIVE`**: the token's tenant does not exist in the database; start the app with the `local`
  profile (it seeds the test tenants) or create the tenant as `platform-admin`.
- **Integration tests fail to start containers**: Docker must be running; on Windows, Testcontainers needs the Docker
  Desktop "Expose daemon" setting or the default named pipe.
- **Paths with spaces** (Windows): the build handles them; run Gradle from the repository root.
