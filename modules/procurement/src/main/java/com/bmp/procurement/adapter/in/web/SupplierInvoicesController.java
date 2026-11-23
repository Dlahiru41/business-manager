package com.bmp.procurement.adapter.in.web;

import com.bmp.procurement.adapter.in.web.generated.api.SupplierInvoicesApi;
import com.bmp.procurement.adapter.in.web.generated.model.CreateSupplierInvoiceRequest;
import com.bmp.procurement.adapter.in.web.generated.model.SupplierInvoice;
import com.bmp.procurement.adapter.in.web.generated.model.SupplierInvoicePage;
import com.bmp.procurement.adapter.in.web.generated.model.SupplierInvoiceStatus;
import com.bmp.procurement.application.command.SupplierInvoiceHandlers;
import com.bmp.procurement.application.query.ProcurementQueryService;
import com.bmp.procurement.domain.model.InvoiceStatus;
import com.bmp.web.etag.Etags;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Supplier invoices (PRO-05). */
@RestController
class SupplierInvoicesController implements SupplierInvoicesApi {

    private final SupplierInvoiceHandlers handlers;
    private final ProcurementQueryService queries;
    private final ProcurementWebMapper mapper;

    SupplierInvoicesController(
            SupplierInvoiceHandlers handlers, ProcurementQueryService queries, ProcurementWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:read')")
    public ResponseEntity<SupplierInvoicePage> listSupplierInvoices(
            @Nullable SupplierInvoiceStatus status,
            @Nullable UUID supplierId,
            @Nullable Integer limit,
            @Nullable String cursor) {
        var page = queries.invoices(
                status == null ? null : InvoiceStatus.valueOf(status.name()), supplierId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new SupplierInvoicePage()
                .items(page.items().stream().map(mapper::invoice).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:invoice')")
    public ResponseEntity<SupplierInvoice> createSupplierInvoice(
            UUID idempotencyKey, CreateSupplierInvoiceRequest request) {
        var invoice = handlers.create(
                request.getSupplierId(),
                request.getSupplierRef(),
                request.getInvoiceDate(),
                request.getDueDate(),
                new BigDecimal(request.getSubtotal()),
                request.getTax() == null ? null : new BigDecimal(request.getTax()),
                request.getReceiptIds());
        return ResponseEntity.created(URI.create("/api/v1/supplier-invoices/" + invoice.id()))
                .eTag(Etags.of(invoice.version()))
                .body(mapper.invoice(invoice));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'procurement:read')")
    public ResponseEntity<SupplierInvoice> getSupplierInvoice(UUID invoiceId) {
        var invoice = queries.invoice(invoiceId);
        return ResponseEntity.ok().eTag(Etags.of(invoice.version())).body(mapper.invoice(invoice));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'procurement:invoice')")
    public ResponseEntity<SupplierInvoice> postSupplierInvoice(UUID invoiceId, UUID idempotencyKey) {
        var invoice = handlers.post(invoiceId);
        return ResponseEntity.ok().eTag(Etags.of(invoice.version())).body(mapper.invoice(invoice));
    }
}
