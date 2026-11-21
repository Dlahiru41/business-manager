# 08 — Security

Baseline: OWASP ASVS level 2 and the OWASP API Security Top 10 (2023). Secure by default: every endpoint is denied unless a rule allows it.

## 1. Authentication

- Keycloak 26.x realm `bmp`; tenants are Keycloak **organizations** (or a `tenant_id` user attribute if organizations are unavailable). A protocol mapper puts `tenant_id`, `roles` and `device_id` (device tokens) into the access token.
- The app is an OAuth2 **Resource Server** validating JWTs (issuer, audience `bmp-api`, signature via JWKS, expiry with ≤ 30 s clock skew).
- Token lifetimes: access 15 min; refresh 8 h for users; device refresh tokens long-lived but revocable (offline tokens) and bound to `device_id`.
- Cashier PIN login: Keycloak handles credentials; a cashier PIN is a separate credential type, usable only from a registered device. Do not build password storage in the app.
- Local/dev: `deploy/local/keycloak/realm-bmp.json` imported at startup with test users per role and two test tenants.

## 2. Tenant resolution

- `TenantContextFilter` (after authentication) reads `tenant_id` from the JWT, validates the tenant is active (cached lookup in the `tenant` module), and sets `TenantContext` + MDC. Missing/invalid → 403 `TENANT_NOT_ACTIVE`.
- Platform administrators may act for a tenant via `X-Tenant-Id` only if their token has the `platform_admin` role; every such request is audited.
- `TenantContext` is a holder around a `ScopedValue` (preferred, Java 25) or a `ThreadLocal` cleared in `finally`. It must never leak between requests; a test proves it.

## 3. Authorization

- **Permissions**, not roles, in code: strings `<area>:<action>` (e.g. `catalog:write`, `pricing:write`, `sales:void`, `inventory:adjust`, `finance:close-period`).
- Roles → permissions mapping is data in the `iam` module (seeded defaults per role below, editable per tenant). The JWT carries roles; `PermissionResolver` expands to permissions (cached per tenant + role set).
- Endpoints: `@PreAuthorize("hasPermission(null, 'sales:void')")`. Object/attribute rules (discount ceilings, own shift only, location scope) are enforced in application handlers via a `PolicyService` with clear error codes.
- Method security enabled (`@EnableMethodSecurity`). An ArchUnit rule fails if a public controller method lacks `@PreAuthorize` (except explicitly `@PermitAll`-annotated health/discovery endpoints).

Default roles:

| Role | Permissions (summary) |
| --- | --- |
| `OWNER` | all tenant permissions |
| `MANAGER` | catalog:*, pricing:write (if tenant setting allows), inventory:*, procurement:*, sales:*, payments:*, reports:read |
| `CASHIER` | sales:create, sales:discount (≤ tenant limit), payments:capture, drawer:operate-own, catalog:read, pricing:read, party:read, party:create-customer |
| `STOREKEEPER` | inventory:receive, inventory:transform, inventory:adjust, inventory:count, procurement:receive, catalog:read |
| `ACCOUNTANT` | finance:*, reports:*, payments:read, sales:read |
| `DEVICE` | sync:*, documents:print (the till's own credential) |

## 4. Input handling

- Bean Validation on all inputs; size limits on strings and collections (e.g. max 500 lines per bill, max 1,000 records per sync batch).
- Request body size limit 1 MB (sync uploads 5 MB) at the server.
- Reject unknown JSON properties on requests.
- Only parameterized SQL (`JdbcClient` named params, JPQL params). String-built SQL is forbidden (ArchUnit/Error Prone check for `+` inside SQL strings).
- Sort/filter fields from allow-lists only.
- Uploaded files (imports, attachments): content-type sniffing, size limits, stored in object storage with random keys, never executed, CSV formula injection neutralised on export.

## 5. Transport and headers

- TLS terminates at the ingress/gateway (TLS 1.2+); HSTS enabled.
- Security headers (Spring Security defaults): `X-Content-Type-Options`, `Cache-Control: no-store` on API responses with business data, strict CORS allow-list from configuration.
- Actuator on a separate management port, not exposed publicly; only `health` is anonymous.

## 6. Secrets

- Never in code, Git, images, or logs. Local: `.env` (git-ignored) consumed by compose. Prod: Kubernetes secrets populated from a vault (Vault / cloud secret manager) via External Secrets.
- Webhook signing secrets and device credentials are generated with `SecureRandom`, shown once, stored hashed where possible.

## 7. Data protection

- No card data. Card payments store only the terminal approval reference; reject any field resembling a PAN.
- Personal data minimised; `@Sensitive` types masked in `toString` and logs.
- Encryption at rest by the managed database/storage; per-tenant keys only in the Silo tier (phase 06).
- Sri Lanka Personal Data Protection Act: support export and anonymisation per customer (see `06-persistence-and-data.md` §9). Record an ADR noting legal review is pending.

## 8. Tamper resistance for money and stock

- Ledger tables are INSERT/SELECT only for `bmp_app` (see `06-persistence-and-data.md` §2).
- Completed bills immutable; void/return require reason + approver with permission; manager-PIN overrides are recorded with both actors.
- Per-register bill numbers are checked for gaps on sync; gaps raise a WARN log, a metric and an owner notification.
- Daily ledger hash per tenant written to the audit chain.

## 9. Supply chain and CI

- Dependency vulnerability scan (OWASP Dependency-Check or Trivy) fails the build on CVSS ≥ 7 without a documented suppression (with expiry date).
- SBOM (CycloneDX) generated per build.
- Images built from minimal bases (Paketo / distroless), run as non-root, read-only filesystem.
- Renovate/Dependabot for updates; Spring Boot patch updates applied monthly.

## 10. Security tests (required)

- 401 without token, 403 without permission — per endpoint (parameterized test generated from the OpenAPI `x-permission` values).
- Tenant isolation per module (`TenantIsolationIT`).
- IDOR test: tenant A's id used with tenant B's token → 404.
- Masking test for logs.
- Rate limiting test on login-adjacent and sync endpoints.
