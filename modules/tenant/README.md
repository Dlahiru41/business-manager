# Tenant module

The most upstream module. A **tenant** is one business using the platform; every other row belongs to exactly one
tenant. This module owns the tenant itself, its **locations** (shops, store rooms, warehouses), its **business
settings** and its **receipt templates**. It depends on no other module.

## Aggregates

| Aggregate | Table | Notes |
| --- | --- | --- |
| `Tenant` | `tenant.tenant` | Id is the tenant id. Status `ACTIVE`, `SUSPENDED` (read-only, TEN-04) or `CLOSED`. Currency, time zone and cut-off mirror the `general` settings group. |
| `Location` | `tenant.location` | Created active; deactivated, never deleted. Name unique per tenant (case-insensitive). |
| `ReceiptTemplate` | `tenant.receipt_template` | Paper 58 or 80 mm, language en/si/ta, JSON header/footer/fields. At most one default per tenant. |

Settings are not an aggregate. Current values live in `tenant.setting` (only values that differ from the defaults),
every change is appended to `tenant.setting_history`, and the **group version** is the highest row version.

## Rules

| Id | Rule | Where |
| --- | --- | --- |
| TEN-01 | A tenant has at least one location: creating a tenant creates "Main shop"; the last active location cannot be deactivated (409 `LAST_ACTIVE_LOCATION`). | `CreateTenantHandler`, `LocationHandlers` |
| TEN-02 | Currency and time zone cannot change once business documents may exist (409 `SETTING_LOCKED`), except for callers with `platform:tenants`. Decided by the `BusinessActivityProbe` SPI; the default says "yes" once the tenant is older than one day. Sales replaces it in phase 03. | `UpdateSettingsHandler`, `TenantAgeActivityProbe` |
| TEN-03 | Settings are validated against per-group JSON schemas (`src/main/resources/settings/*.schema.json`), unknown keys are rejected (422 `UNKNOWN_SETTING`), every change is versioned (history row, group version, `SettingsChanged`) and audited. | `SettingsCatalog`, `UpdateSettingsHandler` |
| TEN-04 | Suspended tenants: every non-GET request is 403 `TENANT_SUSPENDED`. | `TenantContextFilter` (web-support) with `TenantStatusLookup` (60 s cache, evicted on change) |

## Settings groups (defaults)

`general` (currency LKR, timeZone Asia/Colombo, businessDayCutoffHour 0, languages [en]) · `inventory` (costingMethod
MOVING_AVERAGE, negativeStockPolicy ALLOW_AND_FLAG, defaultLocationId = first location) · `sales`
(maxCashierDiscountPct "5.00", returnWindowDays 7, voidRequiresManager true, allowCreditSales false, offlineMaxHours
72, rounding LINE_HALF_UP_2) · `pricing` (pricesTaxInclusive true, derivePriceFromBase false, cashierCanOverridePrice false,
priceChangesRequireOwner false, PRC-08) · `tax` (taxRegistrationNumber null) · `finance` (periodLockDays 5) · `payments` (drawerVarianceAlertAmount
"500.00") · `numbering` (purchaseOrderPrefix "PO-", goodsReceiptPrefix "GRN-", creditNotePrefix "CN-"; ADR-012).

Decimal values are JSON strings. The upper bounds (365 return days, 720 offline hours, 31 lock days) are sanity limits
and can be changed in the schemas.

## Events published

| Event | When |
| --- | --- |
| `TenantCreated` | Tenant created (iam seeds system roles). |
| `LocationCreated` | Location created (including the default one). |
| `SettingsChanged` | A settings group changed. |

Schemas: `contracts/asyncapi/events.yaml`. Events consumed: none.

## API (`contracts/openapi/tenant.yaml`)

| Method | Path | Permission |
| --- | --- | --- |
| POST | `/api/v1/tenants` | `platform:tenants` |
| POST | `/api/v1/tenants/{id}/suspend`, `/reactivate` | `platform:tenants` |
| GET / PATCH | `/api/v1/tenant` | `tenant:read` / `tenant:write` |
| GET / POST | `/api/v1/locations` | `tenant:read` / `tenant:write` |
| GET / PATCH | `/api/v1/locations/{id}` | `tenant:read` / `tenant:write` |
| GET | `/api/v1/settings` | `tenant:read` |
| GET / PATCH | `/api/v1/settings/{group}` | `tenant:read` / `tenant:settings` |
| GET / POST / PATCH | `/api/v1/receipt-templates[/{id}]` | `tenant:write` |

Exercise it with `http/tenant.http`.

## Public API for other modules

- `TenantQueries`: `findTenant(TenantId)`, `isActiveLocation(LocationId)`.
- `TenantProvisioning`: `provision(TenantId, NewTenant)`, idempotent creation with a known id (local seed, onboarding).
- SPI `BusinessActivityProbe` (implemented downstream with a precedence higher than `Ordered.LOWEST_PRECEDENCE`).

## Personal data

None. Tenant and location names and addresses are business data.
