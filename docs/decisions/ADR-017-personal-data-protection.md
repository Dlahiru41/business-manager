# ADR-017: Personal data protection (legal review pending)

- Status: accepted (provisional until the legal review)
- Date: 2026-11-22
- Phase / task: 00 (08-security.md §7, 06-persistence-and-data.md §9)

## Context
The platform stores data of the tenants' staff and, from phase 02, of their customers and suppliers. Sri Lanka's
Personal Data Protection Act, No. 9 of 2022, applies. Nobody on the project has had the platform's processing reviewed
by a lawyer yet, and the obligations that depend on that review (lawful basis per purpose, retention periods,
cross-border transfer of data to the hosting region, breach notification) cannot be settled by engineering alone.

## Options considered
1. Wait for the legal review before storing any personal data. Cons: blocks every phase.
2. **Build to conservative engineering defaults now, keep them configurable, and record the open legal points.**

## Decision
Option 2, with these defaults:
- **Minimise.** iam stores only a display name per user; usernames, e-mail addresses and credentials stay in Keycloak.
  Events and audit records carry ids only (payloads are represented by hashes). Audit records keep the IP address
  and user agent of API writes for security investigations.
- **Mask.** Types holding personal data or credentials are `@Sensitive` and mask `toString()` (ArchUnit enforces the
  override); the log masker removes tokens, e-mail addresses, phone numbers and card-like numbers.
- **Isolate.** Every row belongs to one tenant, enforced by row-level security that fails closed (ADR-013).
- **Export and anonymise** per data subject arrive with the party module (phase 02, 06-persistence-and-data.md §9).
  Ledgers are never edited: anonymisation replaces references, not ledger rows.
- **Retention** of audit records and logs follows tenant settings (phase 04); until then, logs 30 days and audit
  records indefinitely.

## Consequences
- Open points for the legal review, tracked in `docs/PROGRESS.md`: lawful basis and notice texts per purpose,
  retention periods per data category, whether IP addresses in the audit trail need a separate notice, the hosting
  region and cross-border transfer, the breach notification procedure, and the processor agreement between the
  platform operator and each tenant.
- The review may tighten any default above; none of them is hard to change.
