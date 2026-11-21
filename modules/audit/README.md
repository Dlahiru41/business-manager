# Audit module (skeleton)

The audit trail as data (05-logging-and-observability.md §9), separate from logs and retained for years. Depends on no
module: it records API writes through an interceptor and listens to every `DomainEvent` generically.

## Ledger

`audit.audit_record` is a ledger table: `bmp_app` may only `INSERT` and `SELECT`, and row-level security scopes it
to the tenant. Each tenant has one **hash chain**: `sequence` starts at 1 without gaps, `prev_hash` is the previous
record's hash (64 zeros for the first) and `hash` is the SHA-256 over every other field in a length-prefixed canonical
form. Appends of one tenant are serialized with a transaction-scoped advisory lock. `AuditService.verify()`
recomputes the chain and names the first broken record; `AuditIT` proves that an edited row is detected.

Instants are stored and hashed to the microsecond (the database precision). User agents are cut at 256 characters.

## What is recorded

| Source | When | Fields |
| --- | --- | --- |
| `API` | Every request but GET/HEAD/OPTIONS that reached a controller, including refused ones (403, 409, 422 ...). Idempotent replays are not recorded again. | `METHOD /path/template`, resource type (collection after `/api/v1/`), resource id (first `...Id` path variable, else the `Location` id), status, actor, device, platform-administrator flag, IP, user agent, request id |
| `EVENT` | Every domain event of every module, after its transaction commits (inbox: once per event) | event type, publishing module, aggregate id, actor, device, request id, event id, SHA-256 of the payload |
| `SYSTEM` | `AuditWriter.record(...)` called by a module (jobs, permission-sensitive reads) | as given |

Requests without a tenant are recorded under the `tenantId` path variable when there is one (platform operations
on a tenant), otherwise not at all; tenant creation is still audited through `TenantCreated`. If an audit record
cannot be written after the change completed, an ERROR is logged for an operator.

## API (`contracts/openapi/audit.yaml`)

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/api/v1/audit-records?resourceType=&resourceId=&from=&to=&limit=&cursor=` | `audit:read` |

Newest first, keyset cursor on the sequence. IP and user agent are stored but not returned.

## Public API for other modules

- `AuditWriter.record(AuditEntry)` joins the caller's transaction.

## Personal data

Ids only, plus IP address and user agent of API writes (needed for security investigations). Retention follows the
tenant's retention settings (phase 04); export and anonymisation follow 06-persistence-and-data.md §9.

## Not yet

- Permission-sensitive reads are not recorded automatically (modules call `AuditWriter` where needed).
- The daily ledger hash and a scheduled chain verification arrive with finance (08-security.md §8).
