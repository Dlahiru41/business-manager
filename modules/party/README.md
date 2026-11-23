# Party module

Customers and suppliers of a tenant, their customer groups and credit terms (phase 02, rules PTY-01..PTY-04).
Contract: [`contracts/openapi/party.yaml`](../../contracts/openapi/party.yaml).

## Aggregates

| Aggregate | Parts | Invariants |
| --- | --- | --- |
| `Party` | address, credit policy | type CUSTOMER, SUPPLIER or BOTH; display name required; phone in E.164, read in LK without a country code (PTY-01); credit limit ≥ 0, payment terms 0..365 days (PTY-03); anonymised parties never change (PTY-04) |
| `CustomerGroup` | — | code unique per tenant (upper case) |

`PhoneNumbers` normalises typed phones and turns a typed phone prefix into an E.164 prefix for search.

## Rules

- **PTY-01** `POST /parties`: cashiers (`party:create-customer`) add customers; suppliers and BOTH need `party:write`.
- **PTY-02** a phone another party of the tenant has is `DUPLICATE_PARTY_PHONE` (409) unless `force=true`, which needs
  `party:write`.
- **PTY-03** `PUT /parties/{id}/credit-policy` needs `party:credit`.
- **PTY-04** `POST /parties/{id}/anonymise` (`party:anonymise`) replaces name, phone, e-mail, tax id and address,
  deactivates the party and keeps its id, type, group and credit terms. Repeating it changes nothing.

Decisions: [ADR-022](../../docs/decisions/ADR-022-party-modelling-choices.md).

## Events out (`contracts/asyncapi/events.yaml`)

`PartyCreated`, `PartyUpdated`: party id, type, display name, group, status, anonymised flag, currency and credit
terms. Never phone, e-mail, tax id or address.

Events in: none.

## Public API for other modules

`api.PartyQueries.findParty(PartyId)` → `PartyInfo` (type, name, group, active, credit terms; no contact details).

## Endpoints

| Area | Permission |
| --- | --- |
| list, get, `parties:search` (prefix on a name word or the phone) | `party:read` |
| create | `party:create-customer` (+ `party:write` for suppliers, BOTH and `force`) |
| update | `party:write` |
| credit policy | `party:credit` |
| anonymise | `party:anonymise` |
| customer groups: list, get / create, update | `party:read` / `party:write` |

Examples: [`http/party.http`](../../http/party.http).

## Persistence

Schema `party`: `party` and `customer_group`, both under row-level security. Search uses a trigram index on the lower
case name (`pg_trgm`, schema `platform`) and a `text_pattern_ops` index on the phone.

## Personal data

`party.display_name`, `phone`, `email`, `tax_id` and the `address_*` columns. `Party`, `Address` and the party read
models are `@Sensitive`: their `toString` hides these values and they are never logged. Anonymisation is the erasure
command (06 §9).
