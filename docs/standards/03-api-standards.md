# 03 — API standards

Every REST endpoint follows these rules. The contract (`contracts/openapi/<module>.yaml`) is written **before** the controller and is the source of truth.

## 1. Contract-first workflow

1. Edit `contracts/openapi/<module>.yaml` (OpenAPI 3.1). Shared components (Problem, Money, Quantity, Page, headers) live in `contracts/openapi/common.yaml` and are referenced with `$ref`.
2. Lint with Spectral (`contracts/.spectral.yaml`, run in the build via a Gradle Exec task or the `spectral` Docker image in CI).
3. The module's Gradle build runs **openapi-generator** (`spring` generator) to produce interfaces and DTO records:
   - `interfaceOnly=true`, `useSpringBoot3=true` (Jakarta; works with Boot 4 — if the pinned generator offers a Boot 4 option, use it), `useJakartaEe=true`, `useTags=true`, `useBeanValidation=true`, `openApiNullable=false`, `skipDefaultInterface=true`, `useResponseEntity=true`.
   - Generated sources go to `build/generated`, never committed. Packages: `com.bmp.<module>.adapter.in.web.generated.api|model`.
4. Controllers implement the generated interface. MapStruct maps generated DTOs ↔ commands/read models.
5. Integration tests validate every request/response against the YAML with `swagger-request-validator` (`libs/test-support` provides `OpenApiValidation.forModule("catalog")`).
6. Run a breaking-change check (`openapi-diff` / `oasdiff` in CI) against `main`.

Every operation in the YAML has: `operationId`, `summary`, `tags`, `security`, request/response **examples**, every error response (`$ref: common.yaml#/components/responses/...`), and `x-permission: <scope>` documenting the required permission.

## 2. Resource design

- Base path `/api/v1`. Plural nouns: `/products`, `/bills`, `/goods-receipts` (kebab-case).
- Nest only for ownership: `/products/{productId}/variants`.
- Non-CRUD actions: `POST /bills/{billId}/complete`, `POST /bills/{billId}/void`. Batch: `POST /prices:batch-upsert` style is allowed only where the phase file specifies it.
- Path ids are UUIDs; validate format (400 on invalid).
- Never expose database internals (sequence numbers, internal flags).

## 3. Versioning and compatibility

- Major version in the path (`/api/v1`). Use Spring Framework 7 API versioning support configured for path-based versions.
- Allowed without a new version: adding endpoints, optional request fields, response fields, enum values **only** where the field is documented as extensible (`x-extensible-enum: true`).
- Breaking changes need `/api/v2` running beside v1, plus `Deprecation` and `Sunset` headers on v1 (≥ 6 months).
- Clients must ignore unknown fields: configure Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false` for responses consumed internally; requests reject unknown fields (`true`) to catch typos.

## 4. Standard headers

| Header | Direction | Rule |
| --- | --- | --- |
| `Authorization: Bearer <jwt>` | in | Required on all endpoints except health and OIDC discovery. |
| `Idempotency-Key` | in | **Required** on every `POST` that creates a resource or performs an action. UUID. |
| `If-Match` | in | **Required** on `PATCH`/`PUT`/`DELETE` of versioned resources. Missing → `428 Precondition Required`. |
| `ETag` | out | On every single-resource `GET` and write response: `W/"<version>"`. |
| `X-Request-Id` | in/out | Accepted if a valid UUID, else generated; always echoed. |
| `traceparent` | in/out | W3C trace context; propagated automatically by Micrometer Tracing. |
| `Location` | out | On `201 Created`. |
| `RateLimit-*`, `Retry-After` | out | When throttled. |
| `Accept-Language` | in | `en`, `si`, `ta`; affects human-readable `title`/`detail` and receipts, never codes. |

## 5. Idempotency (implemented once in `libs/web-support`)

- `IdempotencyFilter` (or `HandlerInterceptor`) applies to endpoints annotated `@Idempotent` (put it on the generated interface implementation method).
- Storage: table `idempotency_record (tenant_id, idem_key, request_hash, status, http_status, response_body, response_headers, created_at, expires_at)`, PK `(tenant_id, idem_key)`.
- Flow: insert `IN_PROGRESS` row (unique) → process → store response → `COMPLETED`.
  - Same key + same request hash + `COMPLETED` → replay stored response with header `Idempotent-Replayed: true`.
  - Same key + different hash → `422` code `IDEMPOTENCY_KEY_REUSED`.
  - Same key while `IN_PROGRESS` → `409` code `REQUEST_IN_PROGRESS` with `Retry-After: 1`.
- Request hash = SHA-256 of method + path + canonical JSON body.
- Records expire after 24 h; cleanup job with ShedLock.
- For POS uploads, the resource id generated on the device **is** the idempotency key.

## 6. Optimistic concurrency

- Aggregates have `@Version long version`. Responses include `version` in the body and `ETag: W/"<version>"`.
- Updates require `If-Match`; mismatch → `412` code `VERSION_CONFLICT` with the current version in the problem body.

## 7. Pagination, filtering, sorting

- Cursor pagination: `?limit=50&cursor=<opaque>`; response envelope:

```json
{ "items": [ ... ], "page": { "limit": 50, "nextCursor": "eyJpZCI6...", "hasMore": true } }
```

- Cursor = base64url of the last item's sort key + id. Default limit 50, max 200 (`400` above).
- Filters: `?status=ACTIVE&categoryId=...&q=text`; ranges `?from=2026-11-01&to=2026-11-30` (inclusive dates, tenant time zone).
- Sort: `?sort=-createdAt,name` with an allow-list per endpoint.
- Sparse fields only where the phase file asks for them.

## 8. Data types in JSON

| Type | Format |
| --- | --- |
| Ids | UUID string |
| Money | `{"amount": "1250.00", "currency": "LKR"}` (string amount) |
| Quantity | `{"value": "0.750", "unit": "l"}` or `{"value": "2", "packagingUnitId": "..."}` |
| Percent | string `"12.50"` |
| Timestamps | RFC 3339 UTC `"2026-11-21T09:42:13Z"` |
| Business dates | `"2026-11-21"` (tenant's local date) |
| Enums | `UPPER_SNAKE_CASE` strings |

## 9. Status codes

| Code | When |
| --- | --- |
| 200 | Read or update with body |
| 201 | Created (with `Location`) |
| 202 | Accepted for async processing; body has `operationId`, `Location: /api/v1/operations/{id}` |
| 204 | Success without body |
| 400 | Malformed JSON, bad UUID, invalid query parameter |
| 401 | Missing / invalid token |
| 403 | Authenticated but not permitted |
| 404 | Not found **or belongs to another tenant** (never reveal existence) |
| 409 | Business state conflict (`BILL_ALREADY_VOIDED`, `CREDIT_LIMIT_EXCEEDED`) |
| 412 | `If-Match` version mismatch |
| 415 | Unsupported media type |
| 422 | Semantic validation failed (field errors, rule violations on input) |
| 428 | `If-Match` required but missing |
| 429 | Rate limited |
| 500 | Unexpected error (generic body, details only in logs) |
| 503 | Dependency unavailable, with `Retry-After` |

Errors always use Problem Details — see `04-error-handling.md`.

## 10. Security on endpoints

- Every operation declares a permission (`x-permission`) and the controller method enforces it with `@PreAuthorize("hasPermission(null, '<permission>')")` (resolved by the custom `PermissionEvaluator`, see `08-security.md`).
- Object-level checks (e.g. cashier can only see own shift) happen in the application layer.
- A test per endpoint proves `401` without a token and `403` without the permission.

## 11. Async operations and webhooks

- Long jobs (imports, exports, period close) return `202` and an `operation` resource: `{id, type, status: PENDING|RUNNING|SUCCEEDED|FAILED, progress, resultUrl, problem}`.
- Webhooks (phase 05): payload = event envelope; header `Webhook-Signature: t=<ts>,v1=<hmac-sha256>`; retries with exponential back-off for 24 h; deliveries logged.

## 12. HTTP request collections

For every endpoint add an example to `http/<module>.http` (IntelliJ HTTP Client format) using variables from `http/http-client.env.json` (local Keycloak token flow). These double as manual smoke tests.
