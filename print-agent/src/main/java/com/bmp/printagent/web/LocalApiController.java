package com.bmp.printagent.web;

import com.bmp.printagent.cloud.CloudLink;
import com.bmp.printagent.printer.Printer;
import com.bmp.printagent.printer.PrinterException;
import com.bmp.printagent.queue.PrintQueue;
import com.bmp.printagent.queue.PrintWorker;
import com.bmp.printagent.receipt.ReceiptJson;
import com.bmp.printagent.receipt.TemplateCache;
import com.bmp.receipt.ReceiptRenderer;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The till's local API: print a receipt, open the drawer, ask how the agent is. A receipt is kept on disk before it
 * is printed: 200 when it came out at once, 202 when it waits for the printer.
 */
@RestController
@RequestMapping("/v1")
class LocalApiController {

    private static final Logger log = LoggerFactory.getLogger(LocalApiController.class);

    private final ReceiptRenderer renderer;
    private final TemplateCache templates;
    private final PrintQueue queue;
    private final PrintWorker worker;
    private final Printer printer;
    private final CloudLink cloud;

    LocalApiController(
            ReceiptRenderer renderer,
            TemplateCache templates,
            PrintQueue queue,
            PrintWorker worker,
            Printer printer,
            CloudLink cloud) {
        this.renderer = renderer;
        this.templates = templates;
        this.queue = queue;
        this.worker = worker;
        this.printer = printer;
        this.cloud = cloud;
    }

    /** The answer to a print request. */
    record Printed(UUID id, PrintQueue.Status status, int attempts) {}

    /** How the agent is. */
    record Status(
            String printer,
            int queued,
            CloudLink.State cloud,
            @Nullable Instant lastCloudContactAt,
            @Nullable Long templateVersion) {}

    /** {@code POST /v1/print} with a documents.yaml {@code ReceiptModel}. */
    @PostMapping("/print")
    ResponseEntity<Printed> print(@RequestBody JsonNode receipt) {
        byte[] document = renderer.escPos(ReceiptJson.receipt(receipt), templates.current());
        UUID id = queue.add(PrintQueue.Source.LOCAL, null, document);
        log.atInfo().addKeyValue("printRequestId", id).log("Print request queued");
        PrintQueue.Request request = worker.print(id);
        Printed answer = new Printed(id, request.status(), request.attempts());
        return request.status() == PrintQueue.Status.PRINTED
                ? ResponseEntity.ok(answer)
                : ResponseEntity.accepted().body(answer);
    }

    /** {@code POST /v1/drawer/open}: now or not at all; a drawer never opens later by surprise. */
    @PostMapping("/drawer/open")
    ResponseEntity<Void> openDrawer() throws PrinterException {
        worker.openDrawer();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    Status status() {
        return new Status(
                printer.status().name(),
                queue.queued(),
                cloud.state(),
                cloud.lastContactAt().orElse(null),
                templates.version().orElse(null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_RECEIPT", "The receipt cannot be read", e.getMessage());
    }

    @ExceptionHandler(PrinterException.class)
    ResponseEntity<ProblemDetail> printerAway(PrinterException e) {
        log.atWarn().addKeyValue("printerStatus", e.status()).log("Cash drawer not opened");
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PRINTER_" + e.status().name(),
                "The printer is not available",
                "The cash drawer opens through the printer, which is "
                        + e.status().name().toLowerCase(Locale.ROOT) + ".");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String title, @Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setType(URI.create(
                "urn:bmp:print-agent:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(title);
        problem.setProperties(Map.of("code", code));
        return ResponseEntity.status(status).body(problem);
    }
}
