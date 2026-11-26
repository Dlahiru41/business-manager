# Sync module

Offline tills: they bootstrap their data, fetch what changed, and upload what they did offline as one ordered batch
(phase 03, SYN-01..SYN-08). Contract: [`contracts/openapi/sync.yaml`](../../contracts/openapi/sync.yaml).

## Rules

- **SYN-01** only device tokens (`DEVICE_REQUIRED`) of an active device bound to an active register
  (`DEVICE_NOT_BOUND`); the register's location applies to every record.
- **SYN-02** `GET /sync/bootstrap`: the register (prefix, last accepted number) and the datasets `catalog`,
  `priceLists`, `taxRates`, `settings` (general, sales and pricing keys), `customers` (no contact details) and
  `stockBalances` of the register's location, each with a version.
- **SYN-03** `GET /sync/changes?cursors=name:version,...`: the datasets whose version changed, whole.
- **SYN-04** `POST /sync/uploads`: up to 1,000 records (`SHIFT_OPEN`, `DRAWER_OPEN`, `BILL`, `VOID`, `RETURN`,
  `CASH_MOVEMENT`, `DRAWER_COUNT`, `SHIFT_CLOSE`, `WASTAGE`), in order, each in its own transaction through
  `sales.api.SalesCommands`, `payments.api.PaymentCommands` or `inventory.api.InventoryCommands`; ACCEPTED, DUPLICATE or
  REJECTED with a Problem. A record depending on a rejected one is rejected with `DEPENDENCY_REJECTED`.
- **SYN-05** the record id is its idempotency key (`sync.upload_record`, stored in the record's own transaction): a
  replay of an accepted record is DUPLICATE, of a rejected one REJECTED again.
- **SYN-06** bill numbers missing below the register's highest one → `BillNumberGapDetected`, once per number.
- **SYN-07** the `Device-Time` header gives the clock skew; beyond five minutes the device is flagged `CLOCK_SKEW`.
- **SYN-08** records older than `offlineMaxHours` are accepted and flagged `LATE_SYNC` (by sales).

Decisions: [ADR-029](../../docs/decisions/ADR-029-sync-modelling-choices.md).

## Events

Out: `BillNumberGapDetected`. In: none.

## Public API for other modules

`api.events.*` only. Sync calls the command and query APIs of sales, payments, inventory, catalog, pricing, party,
tenant and iam; nothing calls sync.

## Endpoints

| Area | Permission |
| --- | --- |
| bootstrap, changes, status | `sync:read` (DEVICE role) |
| uploads | `sync:upload` (DEVICE role) |
