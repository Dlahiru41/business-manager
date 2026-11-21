# Event catalogue

Every event implements `DomainEvent` (see `docs/standards/07-events-and-messaging.md`) and is documented in `contracts/asyncapi/events.yaml`. Type name for externalization: `bmp.<module>.<event-kebab>.v1`. Topic (phase 06): `bmp.<module>.<aggregate>`, key = aggregate id.

## 1. Events

| Event | Publisher | Consumers | Payload (besides the standard envelope) | Phase |
| --- | --- | --- | --- | --- |
| `TenantCreated` | tenant | iam, catalog, finance, pricing | tenantId, businessType, currency, timeZone, templatePackId | 00 |
| `SettingsChanged` | tenant | pricing, sales, inventory, documents, sync | group, changed keys → new values | 00 |
| `LocationCreated` | tenant | inventory | locationId, name, type | 00 |
| `DeviceRegistered` | iam | sync, sales | deviceId, registerId, locationId | 00 |
| `ProductCreated` / `ProductUpdated` | catalog | pricing, inventory, sales, reporting, sync | productId, name, template, baseUom, tracking, categoryId | 01 |
| `VariantCreated` / `VariantUpdated` / `VariantDeactivated` | catalog | pricing, inventory, procurement, sales, reporting, sync | variantId, productId, sku, name, attributeValues, stockStates, sellMode, packagingUnits[{id, code, qtyInBase, stockState, canBuy, canSell}] | 01 |
| `TransformationDefinitionPublished` | catalog | inventory | definitionId, version, kind, lines[], expectedYieldPct | 01 |
| `PriceListChanged` | pricing | sales, sync, reporting | priceListId, newVersion, changed prices[] | 01 |
| `PartyCreated` / `PartyUpdated` | party | sales, finance, sync, reporting | partyId, type (CUSTOMER/SUPPLIER), displayName, groupId, creditPolicy | 02 |
| `GoodsReceived` | procurement | inventory, finance | receiptId, supplierId, locationId, receivedAt, lines[{variantId, unitId, qty, qtyBase, stockState, unitCost, unitCostBase, batchCode, expiresOn}] | 02 |
| `SupplierInvoicePosted` | procurement | finance | invoiceId, supplierId, amount, tax, dueDate, receiptIds[] | 02 |
| `StockReceived` | inventory | reporting | movementId, variantId, locationId, stockState, qtyBase, unitCost, source | 02 |
| `StockIssued` | inventory | finance, reporting | movementIds[], sourceType, sourceId (billId), lines[{sourceLineId, variantId, qtyBase, costAmount}] | 02 |
| `StockTransformed` | inventory | finance, reporting | runId, definitionId, inputs[{variantId, state, qtyBase, cost}], outputs[...], yieldVarianceQty | 02 |
| `StockAdjusted` | inventory | finance, reporting, audit | movementId, reason, variantId, locationId, qtyBase (signed), costAmount | 02 |
| `StockLevelLow` | inventory | notification, reporting (procurement gets reorder data through its `ReorderSuggestionProvider` SPI, implemented by inventory) | variantId, locationId, available, reorderLevel | 02 |
| `StockCountPosted` | inventory | finance, reporting | countId, lines with variance qty and cost | 02 |
| `SaleCompleted` | sales | inventory, payments, finance, documents, reporting, audit | billId, number, channel, customerId, locationId, registerId, deviceId, priceListVersion, lines[{lineId, variantId, unitId, qty, qtyBase, stockState, unitPrice, discount, tax, lineTotal}], totals, tenders[] | 03 |
| `SaleReturned` | sales | inventory, payments, finance, reporting | creditNoteId, billId, lines[{billLineId, qty, qtyBase, amount, restock}], refundTenders[] | 03 |
| `SaleVoided` | sales | inventory, payments, finance, reporting, audit | billId, reason, approvedBy | 03 |
| `PaymentCaptured` | payments | finance, sales, reporting | paymentId, billId or customerId, method, amount, reference, drawerSessionId | 03 |
| `PaymentRefunded` | payments | finance, sales | refundId, paymentId, amount, method, reason | 03 |
| `DrawerReconciled` | payments | finance, notification | sessionId, expected, counted, variance | 03 |
| `BillNumberGapDetected` | sync | notification, audit | registerId, missingNumbers[] | 03 |
| `ExpenseRecorded` | finance | reporting | expenseId, categoryId, amount, paidFrom | 04 |
| `JournalPosted` | finance | reporting, audit | journalId, sourceType, sourceId, lines[] | 04 |
| `ReceivableOverdue` | finance | notification | customerId, balance, daysOverdue | 04 |
| `PeriodClosed` | finance | reporting, audit | period | 04 |

## 2. Bill completion flow (choreography)

1. POS posts the completed bill (online API or sync upload) → **sales** stores it, publishes `SaleCompleted` (with tenders).
2. **payments** records non-credit tenders → `PaymentCaptured` each.
3. **inventory** converts lines to base units, issues stock at current cost (never blocks) → `StockIssued` with `costAmount` per bill line; `StockLevelLow` if a threshold is crossed.
4. **finance** posts revenue + tax on `SaleCompleted`, settlement on `PaymentCaptured`, COGS on `StockIssued` (see `posting-rules.md`).
5. **documents** renders the receipt (reprints / e-mail); live printing happens at the till via the print agent.
6. **reporting** joins `SaleCompleted` and `StockIssued` (by `billId` / `sourceLineId`) into `fact_sale_line`, which gives cost and margin per line and per bill. Sales does not consume `StockIssued`, because that would create a sales ↔ inventory dependency cycle.

## 3. Return flow (choreography; optionally a Temporal workflow in phase 06)

1. `POST /bills/{id}/returns` → validate window, quantities ≤ remaining → credit note → `SaleReturned`.
2. payments: refund per `refundTenders` → `PaymentRefunded` (or credit to customer account).
3. inventory: restock lines with `restock=true` at the **original issue cost**; others → wastage movement.
4. finance: reverse revenue/tax; reverse COGS for restocked lines; refund settlement.

## 4. Purchase flow

1. `POST /goods-receipts` + `/post` → `GoodsReceived`.
2. inventory: receipt movements, moving-average update → `StockReceived`.
3. finance: Dr Inventory / Cr GRNI (goods received not invoiced).
4. `POST /supplier-invoices` matched to receipts → `SupplierInvoicePosted` → finance: Dr GRNI (+ price variance) / Cr Accounts payable.
