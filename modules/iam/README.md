# IAM module

Users, roles and permissions, the caller's identity, and registered devices (tills). Credentials live in Keycloak and
are reached only through the `IdentityProviderGateway` port; this module never accepts or stores a password or a
device secret. Depends on `tenant` (api and events only).

## Aggregates

| Aggregate | Table | Notes |
| --- | --- | --- |
| `AppUser` | `iam.app_user` (+ `iam.user_role`) | Id = identity provider subject (ADR-014). Status `ACTIVE`/`DISABLED`. Role grants with an optional location scope (empty = every location). No username or e-mail is stored. |
| `Role` | `iam.role` (+ `iam.role_permission`) | System roles are seeded per tenant from `SystemRole`; custom roles are created by the tenant. Permission grants use `PermissionPattern` syntax; `platform:*` grants are refused. |
| `Device` | `iam.device` | A till with its identity provider client `device-<id>`. Revocation is final. |

Aggregates with collections start at version 1: Hibernate bumps the version when it writes the collection rows of a
new aggregate. The ETag always equals the stored version.

## Rules

| Id | Rule | Where |
| --- | --- | --- |
| IAM-01 | A tenant keeps at least one active owner: disabling the last one or removing its `OWNER` role is 409 `LAST_OWNER`. Owner changes lock the tenant's owner role row, so concurrent changes are serialized. | `LastOwnerRule`, `OwnerGuard` |
| IAM-02 | System roles cannot be deactivated, and the owner role keeps `*` (409 `SYSTEM_ROLE_IMMUTABLE`). Custom role codes never equal a system role code. | `Role` |
| IAM-03 | Revoked devices are rejected at once on the revoking instance and within 60 s everywhere (403 `DEVICE_REVOKED`); the client is disabled in Keycloak and its secret rotated. | `RevokeDeviceHandler`, `DeviceStatusLookup` |

## Identity provider (ADR-016)

- Accounts get the admin-only attributes `tenant_id` and `bmp_roles` (mapped to the `roles` claim) and the required
  action `UPDATE_PASSWORD`; an e-mail is sent when an address is given (best effort). Users without e-mail get a
  temporary password from an administrator in the Keycloak console.
- Calls are never made inside a database transaction. Order: validate locally, change Keycloak, commit locally; if
  the local commit fails the Keycloak change is undone (`Compensation`, logged at ERROR if the undo fails).
- Adapter: `RestClient` with 2 s connect / 5 s read timeouts, retries (2, exponential back-off) only for idempotent
  calls on I/O errors and 5xx, circuit breaker `keycloak` (metrics through Micrometer). Unavailability is 503
  `IDENTITY_PROVIDER_UNAVAILABLE`.
- Settings `bmp.iam.keycloak.*`; the client secret comes from `BMP_KEYCLOAK_ADMIN_CLIENT_SECRET`.

## Permissions

`RolePermissionResolver` implements the web-support `PermissionResolver` SPI: role codes from the token are expanded
with the tenant's role table, cached 60 s per tenant and role set, evicted after commit on role changes. A system
role not yet seeded falls back to its documented defaults; unknown or inactive custom roles grant nothing.

## Events

| Published | When |
| --- | --- |
| `DeviceRegistered` | A device was registered (never contains the secret). |

| Consumed | Effect |
| --- | --- |
| `TenantCreated` | Seeds the six system roles (`InboxGuard`, safe to repeat). |

Schemas: `contracts/asyncapi/events.yaml`.

## API (`contracts/openapi/iam.yaml`)

| Method | Path | Permission |
| --- | --- | --- |
| GET / POST | `/api/v1/users` | `iam:users` |
| GET / PATCH | `/api/v1/users/{id}` | `iam:users` |
| PUT | `/api/v1/users/{id}/roles` | `iam:users` |
| GET / POST / PATCH | `/api/v1/roles[/{id}]` | `iam:roles` |
| GET | `/api/v1/me`, `/api/v1/me/permissions` | authenticated |
| POST | `/api/v1/devices`, `/api/v1/devices/{id}/revoke` | `iam:devices` |

Exercise it with `http/iam.http`.

## Personal data

Display names only. Usernames, e-mail addresses and credentials stay in Keycloak; `NewIdentity`, `DeviceCredential`
and `Registration` are `@Sensitive` and mask their values in `toString()`.
