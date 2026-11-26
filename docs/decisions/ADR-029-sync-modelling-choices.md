# ADR-029: Sync modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-26
- Phase / task: 03 / T03-08

## Context
SYN-01..SYN-08 fix who syncs, what a till downloads and how an upload is processed, but leave open what a dataset
version is, how "deltas" are formed, how a record's effect and its outcome stay together, what a replay of a rejected
record answers, and how the load budget is met.

## Decisions
1. **Dataset versions are digests.** Each dataset's version is a SHA-256 digest of its content, so it changes exactly
   when the data does, whatever module the data comes from. `GET /sync/changes` sends every dataset whose version
   differs, whole (no row-level deltas yet): correct, simple, and small at pilot volumes (open question: deltas for
   large catalogs; catalog already supports them).
2. **Datasets come through the owning modules' APIs** (`CatalogQueries.tillSnapshot`, `PricingQueries.tillPriceLists`
   and `tillTaxRates`, `PartyQueries.activeCustomers`, `InventoryQueries.available`, `TenantQueries.setting`). Catalog
   and pricing hand over their snapshot records as opaque objects sync serialises unchanged. Customers carry no phone
   or other contact detail. The receipt template comes from `DocumentsQueries.receiptTemplate` (added in T03-09), the
   same template print agents cache.
3. **One transaction per record holds the replay check, the record's effect and its outcome** (`sync.upload_record`,
   unique per tenant, type and id). The owning modules' commands (`SalesCommands`, `PaymentCommands`,
   `InventoryCommands`) join that transaction, so even a command that is not idempotent by itself (a wastage) takes
   effect exactly once, and a batch cut off half-way resumes where it stopped. A business rejection rolls the record
   back and stores its problem in a transaction of its own; a technical failure stops the batch so the till retries.
4. **A replay answers the first outcome**: an accepted record is DUPLICATE, a rejected one stays REJECTED with its
   problem (SYN-05). A record whose dependency was rejected, in this batch or an earlier one, is rejected with
   `DEPENDENCY_REJECTED`. A record that clashes with stored data (an id used before) is rejected, never blocking the
   batch.
5. **Devices.** Only device tokens sync (`DEVICE_REQUIRED`); the device must be active and bound to an active register
   (`DEVICE_NOT_BOUND`), whose location applies to every record (SYN-01).
6. **Number gaps** (SYN-06) are the numbers below the register's highest bill that never arrived; each is stored in
   `sync.reported_gap` and published once in `BillNumberGapDetected`.
7. **Clock skew** (SYN-07) is measured from the `Device-Time` header on every call that sends it; beyond five minutes
   the device is flagged `CLOCK_SKEW` (logged at WARN) until a later call shows the clock right. Late records are
   flagged `LATE_SYNC` by sales (SYN-08).
8. **The load budget** (1,000 records in under 10 s) is mostly database round trips, one transaction per record. The
   test calibrates on 50 records of the same kind: where that shows a local database (at most 10 ms a record) the
   batch must take under 10 s; elsewhere it must scale linearly (at most 1.5 times the calibrated cost per record).
   On Docker Desktop for Windows a round trip costs 1–3 ms and the batch takes about 100 s, so the absolute 10 s is
   not met on such machines. Settings are read once per group and transaction, and the replay check, the effect and
   the outcome share one transaction, to save round trips (open question: batching several records per transaction
   if 10 s must hold there).

## Consequences
- A till can resend a whole day, any number of times; nothing is booked twice.
- Uploads are serial per batch; throughput is bounded by database latency, not by the application.
