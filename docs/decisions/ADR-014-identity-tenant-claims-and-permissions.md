# ADR-014: Identity, tenant claim and permission resolution

- Status: accepted
- Date: 2026-11-21
- Phase / task: 00 / T00-06

## Context
08-security.md says tenants are Keycloak *organizations* "or a `tenant_id` user attribute if organizations are
unavailable". The access token must carry `tenant_id`, `roles` and (for tills) `device_id`. Code checks
*permissions*. Their mapping from roles is per-tenant data in `iam`. Several modules record "who did it"
(`created_by`, `audit_record.actor_id`) and need one stable actor id.

## Options considered
1. Keycloak organizations. Pros: first-class tenancy in the IdP. Cons: the token carries an organization *alias* or a
   map that would need translating to our tenant id, and membership of several organizations would make the tenant
   ambiguous. A user belongs to exactly one tenant in this platform.
2. **A `tenant_id` user attribute mapped to a `tenant_id` claim.** Pros: the claim is the tenant UUID itself, and
   there is exactly one per user. Cons: the attribute must be protected from self-service editing.
3. Look up the tenant from our database per request. Cons: a database hit before any tenant context exists, and a
   second source of truth.

## Decision
- Option 2. The realm's user profile uses `unmanagedAttributePolicy: ADMIN_EDIT`, so only administrators (and the
  `bmp-admin` service account used by `iam`) can set `tenant_id`/`device_id`. Users cannot see or change them.
  Device clients get hardcoded `tenant_id` and `device_id` claim mappers.
- **Actor ids are the IdP subject** (`sub`, a UUID in Keycloak). `UserId` everywhere (`ActorContext`, `created_by`,
  audit) is the subject. `iam.app_user.idp_subject` links a profile to it, so no lookup is needed per request.
- Roles come from the flat `roles` claim (realm-role mapper) or `realm_access.roles`. `PermissionResolver` (SPI in
  web-support, implemented by `iam` from its per-tenant data, cached) expands them into permission patterns
  (`*`, `area:*`, `area:action`). Its default implementation uses the documented `SystemRole` defaults only.
- `platform_admin` holds `platform:*`. It holds tenant permissions only while acting for a tenant through
  `X-Tenant-Id`; such requests are logged and flagged for the audit trail. A tenant user who sends `X-Tenant-Id` is
  rejected (403), never silently ignored.
- Platform endpoints that act on a tenant (`/api/v1/tenants/{tenantId}/...`, permission `platform:tenants`) are the
  one place a tenant id comes from the URL. They are the audited platform path: the audit interceptor records them in
  that tenant's trail, and every platform-administrator request for a tenant is audited (ADR-016).
- Tenant status and device status are SPIs (`TenantStatusProvider`, `DeviceStatusProvider`). Their defaults reject
  everything (fail closed) until the tenant and iam modules provide real lookups.
- Actuator: only `health` and `info` are anonymous. Prometheus/metrics need a `platform_admin` token, on the internal
  management port.

## Consequences
- Moving to Keycloak organizations later only changes the protocol mappers and the claim converter.
- Role permission sets follow 08-security.md §3 literally. Non-owner roles therefore have no `tenant:read` by
  default (recorded as an open question in PROGRESS.md). Tenants can extend roles through the iam API.
- The local realm (`deploy/local/keycloak/realm-bmp.json`) enables the password grant on `bmp-dev-cli` for `.http`
  smoke tests. That is local only and must never appear in a non-local realm.
