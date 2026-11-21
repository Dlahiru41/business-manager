# Build progress

Agents update this file at the end of every task and every phase. Keep it short and factual.

## Status

| Phase | Name | Status | Branch | Closed on |
| --- | --- | --- | --- | --- |
| 00 | Foundation | in progress | phase/00-foundation | |
| 01 | Catalog and pricing | not started | | |
| 02 | Inventory and procurement | not started | | |
| 03 | Sales, payments, sync and printing | not started | | |
| 04 | Finance and reporting | not started | | |
| 05 | Multi-business onboarding | not started | | |
| 06 | Scale-out to microservices | not started | | |

Status values: `not started` · `in progress` · `blocked` · `done`.

## Current phase

- Phase: 00 — Foundation
- Current task: T00-03 Web support
- Next step (exact, so a new session can resume): implement `libs/web-support` (T00-03).

## Task checklist (current phase)

Phase 00:
- [x] T00-01 Repository and build scaffold
- [x] T00-02 Shared kernel (`libs/shared-kernel`)
- [ ] T00-03 Web support (`libs/web-support`)
- [ ] T00-04 Persistence support (`libs/persistence-support`)
- [ ] T00-05 Observability baseline
- [ ] T00-06 Security
- [ ] T00-07 Tenant module
- [ ] T00-08 IAM module
- [ ] T00-09 Audit module (skeleton)
- [ ] T00-10 Local environment and developer experience
- [ ] T00-11 Architecture tests and documentation
- [ ] T00-12 CI pipeline
- [ ] Definition of Done

## Open questions

_One line each: date, question, assumption taken, ADR link._

Known questions from the client (Requirements Understanding Document §9), to be configured, not coded:
- Measured yield: 750 ml bottles per 18 kg can of oil (sets the oil conversion factor).
- Eggs counted singly or by tray; tray size.
- Wholesale price by quantity threshold, by customer, or both.
- Soya meat brands and packet sizes; own packet sizes for loose soya and dried fish.
- Credit customers and limits.
- Payment methods accepted.
- Printer make, model, paper width, connection; sample bill.
- Taxes shown on the bill.
- Number of users, who may change prices, number of billing counters.

## Phase summaries

_Filled in when each phase closes: what was built, ADRs, limitations._
