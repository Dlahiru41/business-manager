# ADR-016: Tenant roles in the identity provider

- Status: accepted
- Date: 2026-11-22
- Phase / task: 00 / T00-08 (amends ADR-014)

## Context
Tokens must carry each user's tenant role codes in the `roles` claim (08-security.md §1). Tenants create their own
roles (`SUPERVISOR`, ...) through the iam API, and the same code may exist in several tenants with different
permissions. ADR-014 took roles from Keycloak realm roles. A realm role must exist before it can be assigned, so the
iam service account (`bmp-admin`) would need `manage-realm` to create custom roles, which also lets it change realm
security settings. Keycloak 26.8 also adds two checks the service account must satisfy: the Admin API audience
(client scope `kc-admin-api`), and an allow-list of protocol mapper types that non-realm administrators may add
(`allowedProtocolMapperTypesForClientAdministrators`).

## Options considered
1. Realm roles for every tenant role, created on demand. Cons: `manage-realm` for the service account, and one global
   role namespace shared by all tenants.
2. **Tenant role codes in an admin-only user attribute `bmp_roles`, mapped to the `roles` claim.** Pros: no realm
   role management at all, per-user data written in the same call that creates the account, and the attribute is
   protected exactly like `tenant_id` (`unmanagedAttributePolicy: ADMIN_EDIT`). Cons: realm-role tooling in the
   Keycloak console does not show tenant roles (the iam API is the place to manage them).
3. Keycloak organizations with organization roles. Cons: same drawbacks as in ADR-014.

## Decision
Option 2.
- `iam` writes `tenant_id` and `bmp_roles` on create and replaces `bmp_roles` on role changes (GET, modify, PUT of
  the full representation, so other attributes are kept). Role changes reach tokens at the next token refresh.
- Realm roles are used only for `platform_admin`. `BmpJwtAuthenticationConverter` reads tenant roles from the flat
  `roles` claim (never `platform_admin` or `DEVICE` there) and takes `platform_admin` only from `realm_access.roles`
  of a token issued to an allowed client (`bmp.security.platform-admin-clients`, empty unless configured). Keycloak's
  built-in realm roles and realm roles set by hand in the console are ignored.
- A device token (a `device_id` claim or an `azp` starting with `device-`) must come from its own client
  `device-<device_id>` and holds exactly the `DEVICE` role, whatever else it claims; anything else is 401. A person
  is never given the `DEVICE` role through the iam API.
- Device clients (`device-<id>`) get hardcoded `tenant_id`, `device_id` and `roles: ["DEVICE"]` claims and the
  `bmp-api` audience. Their secret is generated with `SecureRandom`, returned once and stored nowhere (the
  idempotency record redacts it). Revocation disables the client and rotates its secret.
- The `bmp-admin` client keeps `manage-users`, `manage-clients` and the view/query roles; it gets the `kc-admin-api`
  default client scope, and the realm allow-lists only `oidc-hardcoded-claim-mapper` and `oidc-audience-mapper` for
  client administrators. No `manage-realm`.
- The user id of a profile (`iam.app_user.id`) is the identity provider subject, as ADR-014 requires for actor ids;
  `idp_subject` is kept separately so a later identity provider migration can relink profiles.
- Accounts are created disabled and enabled only after the profile is stored; an account of the same tenant left
  without a profile (a creation whose answer was lost) is adopted on retry.
- A caller grants, creates or changes only roles and permissions their own grants cover (OWASP API5); only a holder
  of `*` manages owners.

## Consequences
- `bmp-admin` can still create clients and set user attributes, so it remains a high-value secret: its compromise
  equals control of the realm's users (as with any `manage-users` holder). Narrowing it with Keycloak fine-grained
  admin permissions is an open question.
- Every non-local realm must have: the `bmp_roles` user-attribute mapper on API clients, `kc-admin-api` on
  `bmp-admin`, and the mapper allow-list. `deploy/local/keycloak/realm-bmp.json` is the reference and
  `KeycloakIdentityProviderIT` proves it against Keycloak 26.8.2.
- Disabling a user ends its sessions; access tokens already issued stay valid until they expire (15 minutes).
  Devices are rejected at once through `DeviceStatusProvider` (IAM-03).
