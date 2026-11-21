# Data model (logical)

The logical tables per module schema. Migrations must turn these into full DDL following `docs/standards/06-persistence-and-data.md`: add `tenant_id` (shown once per table here), `version`, `created_at/by`, `updated_at/by` on mutable tables, PK/unique/check constraints with the naming rules, indexes starting with `tenant_id`, RLS policy, and grants (ledgers INSERT/SELECT only).

Legend: `PK` primary key · `→ x` reference by id (FK only within the same schema) · `[]` array · `{}` jsonb.

## tenant

```sql
tenant            (id PK, name, legal_name, business_type, currency char(3), time_zone, business_day_cutoff_hour int,
                   status CHECK (ACTIVE, SUSPENDED, CLOSED), plan, created_at)          -- no tenant_id: it IS the tenant
location          (id PK, tenant_id, name, type CHECK (SHOP, STORE, WAREHOUSE), address {}, status)
setting           (tenant_id, group_name, key, value {}, version, PK (tenant_id, group_name, key))
number_sequence   (tenant_id, scope, prefix, next_value bigint, PK (tenant_id, scope))   -- scope e.g. 'PO', 'GRN', 'CN', 'REGISTER:<id>'
receipt_template  (id PK, tenant_id, name, paper_width_mm int CHECK (58, 80), header {}, footer {}, fields {}, language, is_default, version)
```

## iam

```sql
app_user          (id PK, tenant_id, idp_subject unique, display_name, status, default_location_id)   -- credentials live in Keycloak
role              (id PK, tenant_id, code, name, system bool)
role_permission   (role_id → role, permission text, PK (role_id, permission))
user_role         (user_id → app_user, role_id → role, location_scope uuid[], PK (user_id, role_id))
device            (id PK, tenant_id, name, register_id, location_id, idp_client_id, status CHECK (ACTIVE, REVOKED), registered_at, last_seen_at)
```

## catalog

```sql
unit_of_measure   (id PK, tenant_id NULL = platform standard, code, name, dimension CHECK (COUNT, MASS, VOLUME, LENGTH, AREA, TIME),
                   to_si_factor numeric(20,10))                       -- g→kg 0.001; ml→l 0.001; piece 1
category          (id PK, tenant_id, parent_id → category, name, path ltree)
product           (id PK, tenant_id, code, name, category_id → category, template, base_uom_id → unit_of_measure,
                   tracking {batches, expiry, serials, decimalQty, weighed}, tax_category_id, attributes {}, status)
product_conversion(product_id → product, from_uom_id, to_uom_id, factor numeric(20,10), source CHECK (DENSITY, MEASURED_YIELD, STANDARD),
                   PK (product_id, from_uom_id, to_uom_id))
attribute_type    (id PK, tenant_id, product_id → product, code, name, sort int)
attribute_value   (id PK, tenant_id, attribute_type_id → attribute_type, code, value, sort int)
variant           (id PK, tenant_id, product_id → product, sku, name, stock_states text[] default '{DEFAULT}',
                   sell_mode CHECK (PREPACKED, BREAK_ON_SALE), status, UNIQUE (tenant_id, sku))
variant_attribute_value (variant_id → variant, attribute_value_id → attribute_value, PK (variant_id, attribute_value_id))
variant_exclusion (product_id → product, attribute_value_ids uuid[], PK (product_id, attribute_value_ids))   -- combos never to generate
packaging_unit    (id PK, tenant_id, variant_id → variant, code, name, qty_in_base numeric(20,6) CHECK (> 0), stock_state,
                   is_base bool, can_buy bool, can_sell bool, is_default_sell bool, status, UNIQUE (variant_id, code))
barcode           (tenant_id, code, packaging_unit_id → packaging_unit, PK (tenant_id, code))
transformation_definition (id PK, tenant_id, code, name, kind CHECK (OPEN, REPACK, BREAK, RECIPE), expected_yield_pct numeric(6,3),
                   def_version int, status CHECK (DRAFT, PUBLISHED, RETIRED))
transformation_line (id PK, definition_id → transformation_definition, direction CHECK (IN, OUT), variant_id → variant,
                   stock_state, qty_base numeric(20,6), cost_share_pct numeric(6,3))   -- OUT lines' cost_share_pct sum to 100
```

## pricing

```sql
price_list        (id PK, tenant_id, code, name, channel CHECK (RETAIL, WHOLESALE, ANY), customer_group_id, customer_id,
                   currency, tax_inclusive bool, priority int, current_version int, status)
price             (id PK, tenant_id, price_list_id → price_list, variant_id, packaging_unit_id, min_qty numeric(20,6) default 1,
                   amount numeric(19,4), valid_from timestamptz, valid_to timestamptz, list_version int,
                   UNIQUE (price_list_id, packaging_unit_id, min_qty, valid_from))
price_list_version(price_list_id → price_list, version int, published_at, published_by, change_summary {}, PK (price_list_id, version))
price_rule        (id PK, tenant_id, code, name, kind CHECK (QTY_BREAK, CUSTOMER_GROUP, PROMOTION, MARKUP), conditions {}, effect {},
                   priority int, valid_from, valid_to, status)
tax_category      (id PK, tenant_id, code, name)
tax_rate          (id PK, tenant_id, tax_category_id → tax_category, code, name, rate_pct numeric(7,4), compound bool, valid_from date, valid_to date)
variant_price_ref (variant_id PK, tenant_id, product_id, packaging_units {}, tax_category_id, status)   -- local replica from catalog events
```

## party

```sql
party             (id PK, tenant_id, type CHECK (CUSTOMER, SUPPLIER, BOTH), display_name, phone, email, tax_id, address {},
                   customer_group_id, status)                                            -- @Sensitive: display_name, phone, email, address
customer_group    (id PK, tenant_id, code, name)
credit_policy     (party_id PK → party, tenant_id, credit_allowed bool, credit_limit numeric(19,4), payment_terms_days int)
```

## inventory

```sql
stock_item        (variant_id PK, tenant_id, product_id, sku, base_uom, stock_states text[], sell_mode, packaging_units {},
                   track_batches bool, track_expiry bool, decimal_qty bool, status)      -- replica from catalog events
location_ref      (location_id PK, tenant_id, name, status)                              -- replica from tenant events
batch             (id PK, tenant_id, variant_id, code, received_at, expires_on date, supplier_id, UNIQUE (tenant_id, variant_id, code))
stock_movement    (id PK, tenant_id, variant_id, location_id, stock_state, batch_id,
                   qty_base numeric(20,6) NOT NULL,            -- + in, − out
                   unit_cost numeric(19,6), cost_amount numeric(19,4),   -- cost_amount signed like qty
                   type CHECK (OPENING, RECEIPT, SALE, SALE_RETURN, SALE_VOID, TRANSFORM_IN, TRANSFORM_OUT, WASTAGE,
                               COUNT_ADJ, CORRECTION, TRANSFER_IN, TRANSFER_OUT),
                   reason_code, source_type, source_id, source_line_id, occurred_at, recorded_at, actor_id, device_id)
                   PARTITION BY RANGE (occurred_at)                                      -- ledger: INSERT/SELECT only
stock_balance     (tenant_id, variant_id, location_id, stock_state, batch_id (nil uuid when none),
                   on_hand numeric(20,6), reserved numeric(20,6), avg_unit_cost numeric(19,6), stock_value numeric(19,4),
                   last_movement_id, low_flag bool, version, PK (tenant_id, variant_id, location_id, stock_state, batch_id))
cost_layer        (id PK, tenant_id, variant_id, location_id, stock_state, batch_id, qty_remaining numeric(20,6),
                   unit_cost numeric(19,6), received_at)                                 -- FIFO only
transformation_def_ref (definition_id, def_version, tenant_id, kind, lines {}, expected_yield_pct, PK (definition_id, def_version))
transformation_run(id PK, tenant_id, definition_id, def_version, location_id, runs numeric(20,6), expected_out {}, actual_out {},
                   yield_variance_qty numeric(20,6), note, occurred_at, actor_id)
reorder_policy    (tenant_id, variant_id, location_id, reorder_level numeric(20,6), reorder_qty numeric(20,6), preferred_supplier_id,
                   PK (tenant_id, variant_id, location_id))
stock_count       (id PK, tenant_id, location_id, status CHECK (OPEN, SUBMITTED, POSTED, CANCELLED), scope {}, started_at, submitted_at, posted_at, posted_by)
stock_count_line  (id PK, count_id → stock_count, variant_id, stock_state, batch_id, system_qty numeric(20,6), counted_qty numeric(20,6),
                   variance_qty numeric(20,6), variance_reason)
```

## procurement

```sql
purchase_order    (id PK, tenant_id, number, supplier_id, location_id, status CHECK (DRAFT, SENT, PARTIALLY_RECEIVED, RECEIVED, CANCELLED),
                   expected_on date, notes, total numeric(19,4), currency)
purchase_order_line (id PK, po_id → purchase_order, variant_id, packaging_unit_id, qty numeric(20,6), unit_cost numeric(19,6), received_qty numeric(20,6))
goods_receipt     (id PK, tenant_id, number, supplier_id, location_id, po_id, status CHECK (DRAFT, POSTED, CANCELLED), received_at, posted_at, posted_by)
goods_receipt_line(id PK, receipt_id → goods_receipt, po_line_id, variant_id, packaging_unit_id, qty numeric(20,6), qty_base numeric(20,6),
                   stock_state, unit_cost numeric(19,6), unit_cost_base numeric(19,6), batch_code, expires_on)
supplier_invoice  (id PK, tenant_id, supplier_id, supplier_ref, invoice_date, due_date, subtotal, tax, total numeric(19,4), status CHECK (DRAFT, POSTED))
invoice_receipt_match (invoice_id → supplier_invoice, receipt_id → goods_receipt, amount numeric(19,4), PK (invoice_id, receipt_id))
```

## sales

```sql
register          (id PK, tenant_id, location_id, code, device_id, bill_prefix, status)
shift             (id PK, tenant_id, register_id → register, cashier_id, opened_at, closed_at, status)
bill              (id PK,                                       -- generated on the device (UUIDv7)
                   tenant_id, number, location_id, register_id, shift_id, device_id, channel CHECK (RETAIL, WHOLESALE),
                   customer_id, price_list_id, price_list_version int,
                   status CHECK (DRAFT, COMPLETED, VOIDED, PARTIALLY_RETURNED, RETURNED),
                   subtotal, discount_total, tax_total, grand_total numeric(19,4), currency,
                   device_totals {},                                     -- as sent by the till when they differ (SAL-04)
                   business_date date, completed_at, synced_at, cashier_id, offline bool, flags text[], version,
                   UNIQUE (tenant_id, number))
bill_line         (id PK, bill_id → bill, line_no int, variant_id, packaging_unit_id, description, qty numeric(20,6), qty_base numeric(20,6),
                   stock_state, weighed bool, unit_price numeric(19,4), line_discount numeric(19,4), allocated_bill_discount numeric(19,4),
                   tax_amount numeric(19,4), line_total numeric(19,4), price_source, price_rule_id, returned_qty numeric(20,6))
                   -- cost and margin are NOT stored in sales; see reporting.fact_sale_line
bill_tender       (id PK, bill_id → bill, payment_id, method, amount numeric(19,4), reference)   -- as captured at the till
bill_discount     (bill_id PK → bill, type CHECK (AMOUNT, PERCENT), value numeric(19,4), reason, approved_by)
credit_note       (id PK, tenant_id, number, bill_id → bill, reason, total numeric(19,4), status, created_at, created_by, approved_by)
credit_note_line  (id PK, credit_note_id → credit_note, bill_line_id → bill_line, qty numeric(20,6), qty_base numeric(20,6), amount numeric(19,4),
                   tax_amount numeric(19,4), restock bool)
```

## payments

```sql
payment           (id PK, tenant_id, bill_id, customer_id, method CHECK (CASH, CARD, BANK_TRANSFER, MOBILE_WALLET, CHEQUE),
                   amount numeric(19,4), currency, tendered numeric(19,4), change_given numeric(19,4), reference, status,
                   drawer_session_id, captured_at, device_id)                                -- ledger; CREDIT is not a payment
refund            (id PK, tenant_id, payment_id → payment, amount numeric(19,4), method, reason, drawer_session_id, created_at, created_by)  -- ledger
drawer_session    (id PK, tenant_id, register_id, opened_by, opening_float numeric(19,4), expected_cash numeric(19,4),
                   counted_cash numeric(19,4), variance numeric(19,4), denominations {}, opened_at, closed_at,
                   status CHECK (OPEN, COUNTED, RECONCILED))
cash_movement     (id PK, tenant_id, drawer_session_id → drawer_session, type CHECK (FLOAT, SALE, REFUND, PAID_OUT, PAID_IN, DROP),
                   amount numeric(19,4), source_type, source_id, occurred_at)                -- ledger
```

## finance

```sql
account           (id PK, tenant_id, code, name, type CHECK (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE), subtype, parent_id, system_key, status,
                   UNIQUE (tenant_id, code))
journal_entry     (id PK, tenant_id, number, entry_date date, period char(7), source_type, source_id, source_event_id UNIQUE,
                   memo, reverses_id, posted_at, posted_by)                                 -- ledger
journal_line      (id PK, tenant_id, journal_id → journal_entry, account_id → account, debit numeric(19,4), credit numeric(19,4),
                   party_type, party_id, dimensions {variantId, productId, channel, locationId, billId})   -- ledger
expense_category  (id PK, tenant_id, code, name, account_id → account)
expense           (id PK, tenant_id, category_id → expense_category, expense_date, amount numeric(19,4), paid_from CHECK (CASH_DRAWER, BANK, PAYABLE),
                   drawer_session_id, supplier_id, description, attachment_ids uuid[], journal_id)
open_item         (id PK, tenant_id, party_type, party_id, document_type, document_id, amount numeric(19,4), settled numeric(19,4),
                   document_date, due_date, status CHECK (OPEN, SETTLED))
party_balance     (tenant_id, party_type, party_id, balance numeric(19,4), oldest_open_date, PK (tenant_id, party_type, party_id))   -- projection
fiscal_period     (tenant_id, period char(7), status CHECK (OPEN, CLOSING, CLOSED), closed_at, closed_by, PK (tenant_id, period))
```

## reporting (read models, rebuilt from events)

```sql
fact_sale_line    (tenant_id, bill_id, bill_line_id PK, business_date, location_id, channel, customer_id, cashier_id,
                   product_id, variant_id, packaging_unit_id, qty, qty_base, revenue, discount, tax, cost, margin)
fact_stock_movement (tenant_id, movement_id PK, business_date, variant_id, location_id, type, reason_code, qty_base, cost_amount)
fact_payment      (tenant_id, payment_id PK, business_date, method, amount, register_id)
fact_expense      (tenant_id, expense_id PK, business_date, category_id, amount)
daily_summary     (tenant_id, business_date, location_id, revenue, cost, margin, expenses, bills, PK (tenant_id, business_date, location_id))
```

## documents, sync, audit, notification

```sql
documents.print_agent (id PK, tenant_id, location_id, name, printer_model, paper_width_mm, status, last_seen_at)
documents.print_job   (id PK, tenant_id, agent_id, document_type, source_id, payload_ref, status CHECK (QUEUED, SENT, PRINTED, FAILED), attempts, created_at)
sync.device_cursor    (device_id PK, tenant_id, dataset, cursor_token, last_sync_at)
sync.upload_record    (tenant_id, device_id, record_type, record_id, status CHECK (ACCEPTED, DUPLICATE, REJECTED), problem {}, received_at,
                       PK (tenant_id, record_type, record_id))
audit.audit_record    (id PK, tenant_id, occurred_at, actor_id, device_id, action, resource_type, resource_id, before_hash, after_hash,
                       ip, user_agent, request_id, prev_hash, hash)                          -- ledger
notification.outbound_message (id PK, tenant_id, channel CHECK (SMS, EMAIL, PUSH, WEBHOOK), recipient_ref, template, payload {}, status, attempts, created_at)
notification.webhook_subscription (id PK, tenant_id, url, event_types text[], secret_hash, status)
```

## platform (shared infrastructure)

```sql
platform.idempotency_record (tenant_id, idem_key, request_hash, status, http_status, response_body {}, response_headers {}, created_at, expires_at,
                             PK (tenant_id, idem_key))
platform.inbox              (consumer, event_id, processed_at, PK (consumer, event_id))
platform.failed_event       (id PK, tenant_id, consumer, event_id, event_type, payload {}, problem {}, attempts, first_failed_at, last_failed_at, status)
platform.event_publication  -- Spring Modulith JDBC registry (create via Flyway using Modulith's schema for the pinned version)
platform.shedlock           (name PK, lock_until, locked_at, locked_by)
platform.operation          (id PK, tenant_id, type, status, progress int, result_ref, problem {}, created_at, updated_at)
```
