package com.bmp.documents.domain.model;

import com.bmp.documents.domain.error.DocumentsErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A receipt to print on an agent (DOC-05): QUEUED until the agent takes it (SENT), then PRINTED or FAILED as the agent
 * reports. A job sent but not reported within a minute is handed out again, so a lost answer never loses a print.
 */
@Entity
@Table(schema = "documents", name = "print_job")
public class PrintJob implements Auditable {

    /** How long a job handed out waits for its result before it is handed out again. */
    public static final Duration RESULT_TIMEOUT = Duration.ofMinutes(1);

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "document_type", nullable = false, updatable = false)
    private String documentType;

    @Column(name = "source_id", updatable = false)
    private @Nullable UUID sourceId;

    /** The receipt as the API carries it (documents.yaml {@code ReceiptModel}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String receipt;

    @Column(name = "template_version", updatable = false)
    private @Nullable Long templateVersion;

    @Column(nullable = false)
    private String status = "QUEUED";

    @Column(nullable = false)
    private int attempts;

    private @Nullable String error;

    @Column(name = "sent_at")
    private @Nullable Instant sentAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected PrintJob() {}

    public static PrintJob queue(
            UUID id,
            TenantId tenant,
            UUID agentId,
            String documentType,
            @Nullable UUID sourceId,
            String receipt,
            @Nullable Long templateVersion) {
        PrintJob job = new PrintJob();
        job.id = Objects.requireNonNull(id, "id");
        job.tenantId = tenant.value();
        job.agentId = agentId;
        job.documentType = documentType;
        job.sourceId = sourceId;
        job.receipt = receipt;
        job.templateVersion = templateVersion;
        return job;
    }

    /** Whether the agent may take it now: queued, or sent without a result for longer than the timeout. */
    public boolean due(Instant now) {
        // A SENT job always has sentAt; without one it counts as sent long ago.
        return "QUEUED".equals(status)
                || ("SENT".equals(status)
                        && !Objects.requireNonNullElse(sentAt, Instant.EPOCH)
                                .plus(RESULT_TIMEOUT)
                                .isAfter(now));
    }

    public void send(Instant at) {
        if (!due(at)) {
            throw stateInvalid();
        }
        this.status = "SENT";
        this.sentAt = at;
        this.attempts++;
    }

    /** The agent's result; a repeated report of the same outcome changes nothing. */
    public void report(boolean printed, @Nullable String failure, Instant at) {
        String outcome = printed ? "PRINTED" : "FAILED";
        if (outcome.equals(status)) {
            return;
        }
        if (!"SENT".equals(status)) {
            throw stateInvalid();
        }
        this.status = outcome;
        this.error = printed ? null : failure;
        this.completedAt = at;
    }

    private BusinessRuleViolation stateInvalid() {
        return new BusinessRuleViolation(DocumentsErrorCode.PRINT_JOB_STATE_INVALID, Map.of("status", status));
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public UUID id() {
        return id;
    }

    public UUID agentId() {
        return agentId;
    }

    public String documentType() {
        return documentType;
    }

    public @Nullable UUID sourceId() {
        return sourceId;
    }

    public String receipt() {
        return receipt;
    }

    public @Nullable Long templateVersion() {
        return templateVersion;
    }

    public String status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public @Nullable String error() {
        return error;
    }

    public @Nullable Instant completedAt() {
        return completedAt;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }
}
