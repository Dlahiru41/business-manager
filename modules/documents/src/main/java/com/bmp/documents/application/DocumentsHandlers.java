package com.bmp.documents.application;

import com.bmp.documents.domain.error.DocumentsErrorCode;
import com.bmp.documents.domain.model.PrintAgent;
import com.bmp.documents.domain.model.PrintJob;
import com.bmp.documents.domain.port.PrintAgentRepository;
import com.bmp.documents.domain.port.PrintJobRepository;
import com.bmp.receipt.ReceiptModel;
import com.bmp.receipt.ReceiptTemplate;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.LocationId;
import com.bmp.tenant.api.TenantQueries;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rules DOC-05, DOC-06: print agents at the shop's locations, reprints queued for the bill's location, jobs handed out
 * by long polling (one agent poll at a time takes a job) and the agents' results.
 */
@Service
public class DocumentsHandlers {

    /** The longest an agent's poll waits for a job. */
    public static final Duration MAX_WAIT = Duration.ofSeconds(30);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    private static final Logger log = LoggerFactory.getLogger(DocumentsHandlers.class);

    private final PrintAgentRepository agents;
    private final PrintJobRepository jobs;
    private final ReceiptSource receipts;
    private final TenantQueries tenants;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters") // the stores and services print jobs need
    public DocumentsHandlers(
            PrintAgentRepository agents,
            PrintJobRepository jobs,
            ReceiptSource receipts,
            TenantQueries tenants,
            JsonMapper json,
            PlatformTransactionManager transactions,
            IdGenerator ids,
            Clock clock) {
        this.agents = agents;
        this.jobs = jobs;
        this.receipts = receipts;
        this.tenants = tenants;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    @Observed(name = "bmp.documents.register-agent")
    public PrintAgent register(
            @Nullable UUID id, UUID locationId, String name, @Nullable String printerModel, int paperWidthMm) {
        if (id != null) {
            Optional<PrintAgent> known = agents.findById(id);
            if (known.isPresent()) {
                return known.get();
            }
        }
        if (!tenants.isActiveLocation(LocationId.of(locationId))) {
            throw new BusinessRuleViolation(DocumentsErrorCode.UNKNOWN_LOCATION, Map.of("locationId", locationId));
        }
        PrintAgent agent = agents.saveAndFlush(PrintAgent.register(
                id != null ? id : ids.newUuid(),
                TenantContext.require(),
                locationId,
                name,
                printerModel,
                paperWidthMm));
        log.atInfo().addKeyValue("agentId", agent.id()).log("Print agent registered");
        return agent;
    }

    /** Rule DOC-05: a COPY of the bill's receipt for the agent named, or the first agent of the bill's location. */
    @Transactional
    @Observed(name = "bmp.documents.reprint")
    public PrintJob reprint(UUID billId, @Nullable UUID agentId) {
        UUID location = receipts.locationOf(billId).orElseThrow(() -> billNotFound(billId));
        PrintAgent agent = agentId != null
                ? agents.findById(agentId)
                        .orElseThrow(() -> new BusinessRuleViolation(
                                DocumentsErrorCode.UNKNOWN_PRINT_AGENT, Map.of("agentId", agentId)))
                : agents.atLocation(location).stream()
                        .findFirst()
                        .orElseThrow(() -> new BusinessRuleViolation(
                                DocumentsErrorCode.NO_PRINT_AGENT, Map.of("locationId", location)));
        ReceiptTemplate template = receipts.template(agent.paperWidthMm());
        ReceiptModel copy = receipts.bill(billId, true).orElseThrow(() -> billNotFound(billId));
        PrintJob job = jobs.saveAndFlush(PrintJob.queue(
                ids.newUuid(),
                TenantContext.require(),
                agent.id(),
                "RECEIPT",
                billId,
                ReceiptJsonWriter.write(json, copy, template.timeZone()),
                template.version()));
        log.atInfo()
                .addKeyValue("printJobId", job.id())
                .addKeyValue("agentId", agent.id())
                .log("Receipt reprint queued");
        return job;
    }

    /**
     * Long polling (DOC-05): the agent's next job, waiting up to {@code wait} for one to arrive; empty when none did.
     * Polling marks the agent as seen.
     */
    @Observed(name = "bmp.documents.next-job")
    public Optional<PrintJob> next(UUID agentId, Duration wait) {
        // A technical timeout, measured on the monotonic timer: the business clock may stand still (tests) or jump.
        long deadline = System.nanoTime() + (wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait).toNanos();
        while (true) {
            Optional<PrintJob> job = Objects.requireNonNull(transaction.execute(s -> {
                PrintAgent agent = agents.findById(agentId).orElseThrow(() -> agentNotFound(agentId));
                Instant now = clock.instant();
                agent.seen("ONLINE", now);
                agents.saveAndFlush(agent);
                Optional<PrintJob> due = jobs.nextDue(agentId, now);
                due.ifPresent(j -> {
                    j.send(now);
                    jobs.saveAndFlush(j);
                });
                return due;
            }));
            if (job.isPresent() || System.nanoTime() - deadline >= 0) {
                return job;
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /** The agent's result for a job; the printer's state is kept on the agent. */
    @Transactional
    @Observed(name = "bmp.documents.job-result")
    public PrintJob report(UUID jobId, boolean printed, @Nullable String error, @Nullable String printerStatus) {
        PrintJob job = jobs.lock(jobId)
                .orElseThrow(
                        () -> new NotFoundException(DocumentsErrorCode.PRINT_JOB_NOT_FOUND, Map.of("jobId", jobId)));
        Instant now = clock.instant();
        job.report(printed, error, now);
        agents.findById(job.agentId()).ifPresent(a -> {
            a.seen(printerStatus != null ? printerStatus : "ONLINE", now);
            agents.saveAndFlush(a);
        });
        PrintJob saved = jobs.saveAndFlush(job);
        if (!printed) {
            log.atWarn()
                    .addKeyValue("printJobId", jobId)
                    .addKeyValue("error", error)
                    .log("Print job failed");
        }
        return saved;
    }

    static NotFoundException billNotFound(UUID billId) {
        return new NotFoundException(DocumentsErrorCode.BILL_NOT_FOUND, Map.of("billId", billId));
    }

    static NotFoundException agentNotFound(UUID agentId) {
        return new NotFoundException(DocumentsErrorCode.PRINT_AGENT_NOT_FOUND, Map.of("agentId", agentId));
    }
}
