package com.bmp.documents.adapter.in.web;

import com.bmp.documents.adapter.in.web.generated.api.ReceiptsApi;
import com.bmp.documents.adapter.in.web.generated.model.RenderReceiptRequest;
import com.bmp.documents.application.ReceiptSource;
import com.bmp.documents.domain.error.DocumentsErrorCode;
import com.bmp.receipt.ReceiptModel;
import com.bmp.receipt.ReceiptRenderer;
import com.bmp.receipt.ReceiptTemplate;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.idempotency.Idempotent;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Receipts rendered to ESC/POS, PDF or text (DOC-01..DOC-04). */
@RestController
class ReceiptsController implements ReceiptsApi {

    private final ReceiptSource receipts;
    private final ReceiptRenderer renderer;
    private final DocumentsWebMapper mapper;

    ReceiptsController(ReceiptSource receipts, ReceiptRenderer renderer, DocumentsWebMapper mapper) {
        this.receipts = receipts;
        this.renderer = renderer;
        this.mapper = mapper;
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'documents:render')")
    public ResponseEntity<Resource> renderReceipt(UUID idempotencyKey, RenderReceiptRequest request) {
        ReceiptTemplate template = receipts.template(
                request.getPaperWidthMm() == null
                        ? null
                        : request.getPaperWidthMm().getValue());
        boolean copy = Boolean.TRUE.equals(request.getCopy());
        ReceiptModel model;
        if (request.getBillId() != null) {
            UUID billId = request.getBillId();
            model = receipts.bill(billId, copy)
                    .orElseThrow(
                            () -> new NotFoundException(DocumentsErrorCode.BILL_NOT_FOUND, Map.of("billId", billId)));
        } else if (request.getReceipt() != null) {
            try {
                model = mapper.receipt(request.getReceipt(), template.timeZone());
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new BusinessRuleViolation(
                        PlatformErrorCode.VALIDATION_FAILED,
                        "The receipt's time is ISO local date and time, its amounts and quantities decimal numbers",
                        Map.of("field", "receipt"));
            }
            model = copy ? model.asCopy() : model;
        } else {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.VALIDATION_FAILED, "A bill id or a receipt", Map.of("field", "billId"));
        }
        return switch (request.getFormat()) {
            case ESCPOS ->
                ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(new ByteArrayResource(renderer.escPos(model, template)));
            case PDF ->
                ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .body(new ByteArrayResource(renderer.pdf(model, template)));
            case TEXT ->
                ResponseEntity.ok()
                        .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                        .body(new ByteArrayResource(
                                renderer.text(model, template).getBytes(StandardCharsets.UTF_8)));
        };
    }
}
