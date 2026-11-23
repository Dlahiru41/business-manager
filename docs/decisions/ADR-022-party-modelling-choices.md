# ADR-022: Party modelling choices left open by the phase file

- Status: accepted
- Date: 2026-11-23
- Phase / task: 02 / T02-02

## Context
PTY-01..PTY-04 fix what a party is, how phones are normalised, who may change credit terms and how personal data is
erased, but leave storage, permissions inside one endpoint and the search open. Each choice below is the most
conservative option that keeps the data correct, private and auditable (CLAUDE.md §1.3).

## Decisions
1. **Phones are E.164, read in Sri Lanka (+94) when typed without a country code**, as PTY-01 says. The default
   calling code is a constant (`PhoneNumbers.DEFAULT_CALLING_CODE`) because the tenant module has no country setting
   yet. A leading `0` is the trunk prefix; 11+ digits starting with `94` are already international.
2. **Address and credit terms are columns of `party`** (embedded values), not a jsonb document and a `credit_policy`
   table as in the logical model: both have fixed parts, belong to the one aggregate, and anonymisation must clear the
   address in place. A check constraint proves an anonymised row holds no contact data.
3. **Duplicate phones are a soft rule (PTY-02)**: there is no unique key, the handler refuses a phone another party of
   the tenant has unless `force=true`. Two concurrent creates may both store the number; that is what `force` allows
   anyway, so no lock is taken.
4. **One endpoint, two permissions.** `POST /parties` needs `party:create-customer` (cashiers add customers at the
   till); suppliers, `BOTH` and `force=true` also need `party:write`, checked in the handler
   (`PARTY_PERMISSION_REQUIRED`, 403). Credit terms need `party:credit`, anonymisation `party:anonymise`.
5. **Events carry the name, group, status and credit terms only** — never phone, e-mail, tax id or address (07 §1).
   The event catalogue asks for the display name, which tills and receipts need. Anonymising publishes `PartyUpdated`
   with the placeholder name, so replicas forget the real one.
6. **Search is a prefix search**: a word of the lower-case name starting with the query (trigram GIN index; `pg_trgm`
   lives in the `platform` schema for every module) or the E.164 phone starting with the number the query stands for
   (btree `text_pattern_ops`). LIKE wildcards in the query are literal. The trigram index cannot lead with
   `tenant_id`; RLS and Hibernate's tenant filter keep the query in the tenant. Names that start with the query come
   first.
7. **Anonymisation is idempotent and final**: the party becomes `INACTIVE` with the name "Anonymised party"; id, type,
   group and credit terms stay so bills, receipts and invoices keep their party. Every later change is
   `PARTY_ANONYMISED`.

## Consequences
- Another default country, or several, needs a tenant setting and a migration of nothing (phones are stored E.164).
- Finance and sales get everything they need from the events except contact details, which stay in this module.

## Open questions
- The seeded MANAGER role has no `party:` permission (08-security.md §3), although PTY-02 speaks of "a manager"
  forcing duplicates. Kept as specified; tenants grant `party:write` through the iam role API. Recorded in
  `docs/PROGRESS.md`.
- Should the default country become a tenant setting?
