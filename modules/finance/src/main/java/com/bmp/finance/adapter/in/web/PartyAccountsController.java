package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.api.PayablesApi;
import com.bmp.finance.adapter.in.web.generated.api.ReceivablesApi;
import com.bmp.finance.adapter.in.web.generated.model.Aging;
import com.bmp.finance.adapter.in.web.generated.model.AllocationRequest;
import com.bmp.finance.adapter.in.web.generated.model.ListSupplierPayments200Response;
import com.bmp.finance.adapter.in.web.generated.model.Statement;
import com.bmp.finance.adapter.in.web.generated.model.SupplierPaidFrom;
import com.bmp.finance.adapter.in.web.generated.model.SupplierPayment;
import com.bmp.finance.adapter.in.web.generated.model.SupplierPaymentRequest;
import com.bmp.finance.application.command.SupplierPaymentHandler;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PartyType;
import com.bmp.finance.domain.service.OpenItemSettler;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PartyAccountsController implements ReceivablesApi, PayablesApi {

    private final SupplierPaymentHandler payments;
    private final FinanceQueries queries;
    private final FinanceWebMapper mapper;

    PartyAccountsController(SupplierPaymentHandler payments, FinanceQueries queries, FinanceWebMapper mapper) {
        this.payments = payments;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<Aging> listReceivables(
            @Nullable LocalDate asOf, Boolean overdueOnly, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(aging(PartyType.CUSTOMER, asOf, overdueOnly, limit, cursor));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<Aging> listPayables(
            @Nullable LocalDate asOf, Boolean overdueOnly, Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(aging(PartyType.SUPPLIER, asOf, overdueOnly, limit, cursor));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<Statement> getCustomerStatement(
            UUID customerId, @Nullable LocalDate from, @Nullable LocalDate to) {
        return ResponseEntity.ok(mapper.statement(queries.statement(PartyRef.customer(customerId), from, to)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<Statement> getSupplierStatement(
            UUID supplierId, @Nullable LocalDate from, @Nullable LocalDate to) {
        return ResponseEntity.ok(mapper.statement(queries.statement(PartyRef.supplier(supplierId), from, to)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ListSupplierPayments200Response> listSupplierPayments(
            @Nullable UUID supplierId, Integer limit, @Nullable String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        var page = PageResponse.of(
                queries.supplierPayments(supplierId, Cursors.id(query.cursor()), query.fetchSize()),
                query,
                p -> List.of(p.id().toString()));
        return ResponseEntity.ok(new ListSupplierPayments200Response()
                .items(page.items().stream().map(mapper::supplierPayment).toList())
                .page(FinanceWebMapper.page(page.page())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:pay-supplier')")
    public ResponseEntity<SupplierPayment> paySupplier(UUID idempotencyKey, SupplierPaymentRequest r) {
        List<AllocationRequest> allocations = r.getAllocations() == null ? List.of() : r.getAllocations();
        var payment = payments.pay(new SupplierPaymentHandler.PaySupplier(
                r.getId(),
                r.getSupplierId(),
                new BigDecimal(r.getAmount()),
                r.getPaidFrom() == SupplierPaidFrom.CASH_DRAWER,
                r.getDrawerSessionId(),
                r.getReference(),
                r.getPaidAt() == null ? null : r.getPaidAt().toInstant(),
                allocations.stream()
                        .map(a -> new OpenItemSettler.Allocation(a.getDocumentId(), new BigDecimal(a.getAmount())))
                        .toList()));
        return ResponseEntity.created(URI.create("/api/v1/supplier-payments/" + payment.id()))
                .body(mapper.supplierPayment(payment));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<SupplierPayment> getSupplierPayment(UUID paymentId) {
        return ResponseEntity.ok(mapper.supplierPayment(queries.supplierPayment(paymentId)));
    }

    private Aging aging(
            PartyType type,
            @Nullable LocalDate asOf,
            @Nullable Boolean overdueOnly,
            Integer limit,
            @Nullable String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        var result = queries.aging(
                type, asOf, Boolean.TRUE.equals(overdueOnly), Cursors.id(query.cursor()), query.fetchSize());
        var page =
                PageResponse.of(result.rows(), query, r -> List.of(r.partyId().toString()));
        return mapper.aging(result, page.page(), page.items());
    }
}
