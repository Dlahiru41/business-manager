package com.bmp.sales.adapter.in.web;

import com.bmp.sales.adapter.in.web.generated.api.BillsApi;
import com.bmp.sales.adapter.in.web.generated.model.Bill;
import com.bmp.sales.adapter.in.web.generated.model.BillPage;
import com.bmp.sales.adapter.in.web.generated.model.BillStatus;
import com.bmp.sales.adapter.in.web.generated.model.CompleteBillRequest;
import com.bmp.sales.adapter.in.web.generated.model.CreateBillRequest;
import com.bmp.sales.adapter.in.web.generated.model.CreditNote;
import com.bmp.sales.adapter.in.web.generated.model.ReturnRequest;
import com.bmp.sales.adapter.in.web.generated.model.UpdateBillRequest;
import com.bmp.sales.adapter.in.web.generated.model.VoidBillRequest;
import com.bmp.sales.application.command.BillHandlers;
import com.bmp.sales.application.query.SalesQueryService;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Bills online (SAL-01..SAL-15): offline bills come through sync. */
@RestController
class BillsController implements BillsApi {

    private final BillHandlers handlers;
    private final SalesQueryService queries;
    private final SalesWebMapper mapper;

    BillsController(BillHandlers handlers, SalesQueryService queries, SalesWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:read')")
    public ResponseEntity<BillPage> listBills(
            @Nullable BillStatus status,
            @Nullable UUID registerId,
            @Nullable UUID customerId,
            @Nullable LocalDate businessDate,
            @Nullable Boolean flagged,
            Integer limit,
            @Nullable String cursor) {
        var page = queries.bills(
                new SalesQueryService.BillSearch(
                        status == null ? null : com.bmp.sales.domain.model.BillStatus.valueOf(status.name()),
                        registerId,
                        customerId,
                        businessDate,
                        Boolean.TRUE.equals(flagged)),
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(mapper.billPage(page));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:create')")
    public ResponseEntity<Bill> createBill(UUID idempotencyKey, CreateBillRequest request) {
        boolean complete = Boolean.TRUE.equals(request.getComplete());
        if (complete && (request.getTenders() == null || request.getTotals() == null)) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A completed bill carries its tenders and totals",
                    Map.of("field", "tenders"));
        }
        var outcome = handlers.submit(
                new BillHandlers.SubmitBill(
                        request.getId(),
                        request.getNumber(),
                        request.getRegisterId(),
                        request.getShiftId(),
                        SalesChannel.valueOf(request.getChannel().name()),
                        request.getCustomerId(),
                        mapper.lines(request.getLines()),
                        mapper.discount(request.getBillDiscount()),
                        complete,
                        mapper.tenders(request.getTenders()),
                        mapper.totals(request.getTotals()),
                        null,
                        null,
                        null,
                        request.getCreditApprovedBy()),
                Mode.ONLINE);
        var bill = outcome.bill();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/bills/" + bill.id()))
                .eTag(Etags.of(bill.version()))
                .body(mapper.bill(bill));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:read')")
    public ResponseEntity<Bill> getBill(UUID billId) {
        return ok(queries.bill(billId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:create')")
    public ResponseEntity<Bill> updateBill(UUID billId, UpdateBillRequest request, @Nullable String ifMatch) {
        return ok(handlers.update(
                billId,
                IfMatch.expectedVersion(ifMatch),
                new BillHandlers.UpdateBill(
                        request.getChannel() == null
                                ? null
                                : SalesChannel.valueOf(request.getChannel().name()),
                        request.getCustomerId(),
                        request.getLines() == null ? null : mapper.lines(request.getLines()),
                        mapper.discount(request.getBillDiscount()))));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:create')")
    public ResponseEntity<Bill> completeBill(UUID billId, UUID idempotencyKey, CompleteBillRequest request) {
        return ok(handlers.complete(
                billId,
                mapper.tenders(request.getTenders()),
                java.util.Objects.requireNonNull(mapper.totals(request.getTotals())),
                request.getCreditApprovedBy()));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:void')")
    public ResponseEntity<Bill> voidBill(UUID billId, UUID idempotencyKey, VoidBillRequest request) {
        return ok(handlers.voidBill(billId, request.getReason(), request.getApprovedBy(), null, Mode.ONLINE));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:return')")
    public ResponseEntity<CreditNote> returnBill(UUID billId, UUID idempotencyKey, ReturnRequest request) {
        var note = handlers.returnBill(
                billId,
                new BillHandlers.ReturnBill(
                        request.getId(),
                        request.getReason(),
                        request.getLines().stream()
                                .map(l -> new com.bmp.sales.domain.model.Bill.ReturnLine(
                                        l.getBillLineId(), new BigDecimal(l.getQty()), l.getRestock()))
                                .toList(),
                        request.getRefundTenders() == null
                                ? List.of()
                                : request.getRefundTenders().stream()
                                        .map(r -> new BillHandlers.RefundInput(
                                                TenderMethod.valueOf(
                                                        r.getMethod().getValue()),
                                                new BigDecimal(r.getAmount()),
                                                r.getReference()))
                                        .toList(),
                        request.getApprovedBy(),
                        null),
                Mode.ONLINE);
        String currency = queries.bill(billId).currency();
        return ResponseEntity.created(URI.create("/api/v1/credit-notes/" + note.id()))
                .body(mapper.creditNote(note, currency));
    }

    private ResponseEntity<Bill> ok(com.bmp.sales.domain.model.Bill bill) {
        return ResponseEntity.ok().eTag(Etags.of(bill.version())).body(mapper.bill(bill));
    }
}
