# ADR-027: Business activity and credit exposure SPIs

- Status: accepted
- Date: 2026-11-26
- Phase / task: 03 / T03-05

## Context
TEN-02 locks a tenant's currency and time zone "once any business document exists". Phase 00 declared the
`tenant.api.spi.BusinessActivityProbe` with a conservative default (a tenant older than one day is assumed to have
documents) and said sales replaces it in phase 03, the highest-precedence probe winning. Sales knows bills only, while
goods receipts, stock openings and purchase orders (phase 02) carry money in the tenant currency too. SAL-07 asks for a
default credit exposure until finance (phase 04) knows receivables.

## Decisions
1. **The strongest answer of all probes counts** (`NONE` < `ASSUMED` < `CONFIRMED`), instead of the highest-precedence
   probe alone. A downstream probe knows its own documents only, so its `NONE` must not overrule another module's
   documents or the default guess. With sales: a tenant with bills is `CONFIRMED` (nobody may change currency or time
   zone); a young tenant without bills stays `NONE`; an older tenant without bills stays `ASSUMED` (platform
   administrators only), exactly as before phase 03.
2. **Any bill counts**, drafts included: a draft carries the currency it was opened in. Sales asks in its own read-only
   transaction under the given tenant, so row-level security sees exactly that tenant's bills.
3. **Other modules may add probes** (inventory: any stock movement; procurement: any order) without changing tenant;
   until they do, their documents are covered by the default guess (open question).
4. **Default credit exposure** (`CreditExposureProvider`, `@Fallback`): Σ CREDIT tenders of the customer's completed,
   partially returned and returned bills, less CREDIT refunds on their credit notes; voided bills owe nothing.
   Collections are not known to sales, so the figure only ever overstates what the customer owes (SAL-07). A credit
   bill's return refunds to the account (CREDIT) unless other refund tenders are given.

## Consequences
- An old tenant without bills still needs a platform administrator to change currency or time zone; one with bills
  cannot change them at all.
- Finance replaces the exposure in phase 04 by declaring its own `CreditExposureProvider` bean.
