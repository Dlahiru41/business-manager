package com.bmp.documents.domain.model;

import com.bmp.shared.audit.Auditable;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The print agent at a location: the program on the shop PC that drives its receipt printer (DOC-05). */
@Entity
@Table(schema = "documents", name = "print_agent")
public class PrintAgent implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "printer_model")
    private @Nullable String printerModel;

    @Column(name = "paper_width_mm", nullable = false)
    private int paperWidthMm;

    /** ONLINE, OFFLINE, PAPER_OUT or ERROR, as the agent last reported. */
    @Column(nullable = false)
    private String status = "OFFLINE";

    @Column(name = "last_seen_at")
    private @Nullable Instant lastSeenAt;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected PrintAgent() {}

    public static PrintAgent register(
            UUID id, TenantId tenant, UUID locationId, String name, @Nullable String printerModel, int paperWidthMm) {
        if (paperWidthMm != 58 && paperWidthMm != 80) {
            throw new IllegalArgumentException("Receipts are 58 or 80 mm wide");
        }
        PrintAgent agent = new PrintAgent();
        agent.id = Objects.requireNonNull(id, "id");
        agent.tenantId = tenant.value();
        agent.locationId = locationId;
        agent.name = name;
        agent.printerModel = printerModel;
        agent.paperWidthMm = paperWidthMm;
        return agent;
    }

    /** The agent polled or reported: it is alive, and its printer is in this state. */
    public void seen(String printerStatus, Instant at) {
        this.status = printerStatus;
        this.lastSeenAt = at;
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

    public UUID locationId() {
        return locationId;
    }

    public String name() {
        return name;
    }

    public @Nullable String printerModel() {
        return printerModel;
    }

    public int paperWidthMm() {
        return paperWidthMm;
    }

    public String status() {
        return status;
    }

    public @Nullable Instant lastSeenAt() {
        return lastSeenAt;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }
}
