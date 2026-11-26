package com.bmp.documents.adapter.in.web;

import com.bmp.documents.adapter.in.web.generated.api.PrintAgentsApi;
import com.bmp.documents.adapter.in.web.generated.model.PrintAgent;
import com.bmp.documents.adapter.in.web.generated.model.PrintAgentPage;
import com.bmp.documents.adapter.in.web.generated.model.PrintJob;
import com.bmp.documents.adapter.in.web.generated.model.PrintJobResult;
import com.bmp.documents.adapter.in.web.generated.model.ReceiptTemplate;
import com.bmp.documents.adapter.in.web.generated.model.RegisterPrintAgentRequest;
import com.bmp.documents.adapter.in.web.generated.model.ReprintRequest;
import com.bmp.documents.application.DocumentsHandlers;
import com.bmp.documents.application.PrintAgentQueries;
import com.bmp.documents.application.ReceiptSource;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Print agents, their template and jobs, reprints (DOC-05, DOC-06). */
@RestController
class PrintAgentsController implements PrintAgentsApi {

    private final DocumentsHandlers handlers;
    private final PrintAgentQueries queries;
    private final ReceiptSource receipts;
    private final DocumentsWebMapper mapper;

    PrintAgentsController(
            DocumentsHandlers handlers, PrintAgentQueries queries, ReceiptSource receipts, DocumentsWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.receipts = receipts;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'documents:print')")
    public ResponseEntity<PrintAgentPage> listPrintAgents(
            @Nullable UUID locationId, Integer limit, @Nullable String cursor) {
        var page = queries.agents(locationId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new PrintAgentPage()
                .items(page.items().stream().map(mapper::agent).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'documents:print')")
    public ResponseEntity<PrintAgent> registerPrintAgent(UUID idempotencyKey, RegisterPrintAgentRequest request) {
        var agent = handlers.register(
                request.getId(),
                request.getLocationId(),
                request.getName(),
                request.getPrinterModel(),
                request.getPaperWidthMm().getValue());
        return ResponseEntity.created(URI.create("/api/v1/print-agents/" + agent.id()))
                .body(mapper.agent(agent));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'documents:print')")
    public ResponseEntity<PrintAgent> getPrintAgent(UUID agentId) {
        return ResponseEntity.ok(mapper.agent(queries.agent(agentId)));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'documents:print')")
    public ResponseEntity<ReceiptTemplate> getReceiptTemplate(UUID agentId) {
        var agent = queries.agent(agentId);
        return ResponseEntity.ok(mapper.template(receipts.template(agent.paperWidthMm())));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'documents:print')")
    public ResponseEntity<PrintJob> nextPrintJob(UUID agentId, Integer waitSeconds) {
        return handlers.next(agentId, Duration.ofSeconds(waitSeconds))
                .map(job -> ResponseEntity.ok(mapper.job(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'documents:print')")
    public ResponseEntity<PrintJob> reportPrintJobResult(UUID jobId, UUID idempotencyKey, PrintJobResult result) {
        var job = handlers.report(
                jobId,
                result.getStatus() == PrintJobResult.StatusEnum.PRINTED,
                result.getError(),
                result.getPrinterStatus() == null
                        ? null
                        : result.getPrinterStatus().getValue());
        return ResponseEntity.ok(mapper.job(job));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:read')")
    public ResponseEntity<PrintJob> reprintBill(UUID billId, UUID idempotencyKey, ReprintRequest request) {
        var job = handlers.reprint(billId, request.getAgentId());
        return ResponseEntity.created(URI.create("/api/v1/print-jobs/" + job.id()))
                .body(mapper.job(job));
    }
}
