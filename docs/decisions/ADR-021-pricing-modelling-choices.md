# ADR-021: Pricing modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-22
- Phase / task: 01 / T01-05..T01-06

## Context
The phase file fixes the pricing rules (PRC-01..PRC-10) and endpoints but leaves storage, ordering and several edge
cases open. Each choice below is the most conservative option that keeps prices correct, reproducible and auditable
(CLAUDE.md §1.3).

## Decisions
1. **Prices are never edited in place once in force.** An upsert row without `validFrom` ends the open price at
   "now" and starts a new one; a row whose `validFrom` equals an existing price's start updates that row (a correction
   before or at the same instant); a row overlapping another dated price is refused (`PRICE_OVERLAP`). So the price a
   sale used can always be found again by list version and instant.
2. **List versions are an append-only ledger.** `price_list_version` stores, per version, who changed what
   (`change_summary`, a jsonb array of the prices CREATED, UPDATED or ENDED, with amount and validity). The
   application role may only insert and select. Upserts lock the list row (`SELECT … FOR UPDATE`), so concurrent upserts of one list are serialised and the version grows by exactly
   one per change.
3. **Rule conditions are typed columns** (`cond_channel`; uuid arrays `cond_price_list_ids`,
   `cond_customer_group_ids`, `cond_customer_ids`, `cond_product_ids`, `cond_variant_ids`, `cond_packaging_unit_ids`;
   `cond_min_qty_base`), not a free JSON document: the database checks their types and the
   resolver matches them without parsing. New condition kinds need a migration, which is deliberate.
4. **Price lists and price rules are paged in memory** with an id cursor, after ordering by priority and code. A
   tenant has tens of lists and rules, not thousands; keyset paging on (priority, code, id) is deferred until a tenant
   needs it. Prices, versions and tax rates page in SQL.
5. **The variant replica keeps the highest aggregate version.** `variant_price_ref` is upserted only when the event's
   `aggregateVersion` is newer; a `VariantDeactivated` that arrives before its `VariantCreated` leaves an inactive
   tombstone, so the late create cannot revive the variant. Unknown or inactive variants and units that are not sold
   resolve as `NOT_SELLABLE`, never as an error for the whole basket.
6. **Resolution is a query.** `POST /api/v1/prices:resolve` changes nothing, so it takes no `Idempotency-Key` and stores
   nothing; a sale (phase 02) records the list id, version and source each line returned (PRC-10).
7. **PRC-08 owner restriction is a tenant setting** (`pricing.priceChangesRequireOwner`, default false). It covers
   creating and changing price lists, prices and rules. Platform administrators acting for the tenant
   (`X-Tenant-Id`) pass, as they may for tenant settings (TEN-02).
8. **Tax is computed per line and then summed**, each tax rounded per line with the tenant's rounding policy and the
   net absorbing the rounding, so net + taxes equals the gross exactly. Inclusive prices are back-calculated; compound
   rates apply to the net plus the taxes before them.
9. **Retail and Wholesale are seeded on `TenantCreated`** in the tenant's currency, tax-inclusive per the tenant
   setting, so a new tenant can price immediately. They are ordinary lists afterwards (renamable, deactivatable).
10. **A price applies from its minimum quantity, also for weighed units.** PRC-02 sets the default `minQty` to 1 and
   PRC-04 picks the row with the highest `minQty` ≤ quantity, so 0.75 kg finds no kilogram price at the default
   minimum. Rather than letting a price apply below its stated minimum, a tenant gives the kilogram price of loose
   goods a minimum such as 0.001 (one gram); the pilot fixture does so. `minQty` must be greater than 0
   (`VALIDATION_FAILED`).

## Consequences
- Price history and every till's view of a list can be reproduced from `price` and `price_list_version` alone.
- Adding a rule condition kind costs a migration and a contract change (additive).
- If a tenant ever holds thousands of rules, item 4 must move to keyset paging; the cursor format stays opaque.

## Open questions
- Should the default `minQty` of weighed units be below 1 (item 10)? Kept at 1 as specified until the client decides.
- The seeded MANAGER role holds `pricing:write` but not `pricing:read`, so a manager may change prices yet cannot list
  price lists or resolve a basket. The role mapping is the client's business decision and stays as specified; tenants
  can grant `pricing:read` to MANAGER through the IAM role API.

Both are recorded in `docs/PROGRESS.md`.
