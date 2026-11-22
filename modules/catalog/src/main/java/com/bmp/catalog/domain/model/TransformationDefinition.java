package com.bmp.catalog.domain.model;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A recipe that turns input stock into output stock (open a can, repack loose fish, break a tray) — aggregate root of
 * its lines. Rule CAT-10: at least one IN and one OUT line; the OUT lines' cost shares sum to 100 (a single OUT line
 * defaults to 100); publishing takes the next version number and freezes that version; editing a published definition
 * starts a new draft. Definitions are retired, never deleted (CAT-11).
 */
@Entity
@Table(schema = "catalog", name = "transformation_definition")
public class TransformationDefinition implements Auditable {

    public static final int MAX_LINES = 50;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransformationKind kind;

    @Column(name = "expected_yield_pct", precision = 6, scale = 3)
    private @Nullable BigDecimal expectedYieldPct;

    /** The latest published version; 0 while never published. */
    @Column(name = "def_version", nullable = false)
    private int defVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DefinitionStatus status;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "definition_id", nullable = false)
    @OrderBy("lineNo ASC")
    private List<TransformationLine> lines = new ArrayList<>();

    /** Null until first stored, so Spring Data persists (not merges) new aggregates with assigned ids. */
    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected TransformationDefinition() {}

    private TransformationDefinition(
            UUID id,
            TenantId tenant,
            String code,
            String name,
            TransformationKind kind,
            @Nullable BigDecimal expectedYieldPct,
            List<LineDraft> lines,
            Supplier<UUID> ids) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = tenant.value();
        this.code = code.toUpperCase(Locale.ROOT);
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.expectedYieldPct = expectedYieldPct;
        this.status = DefinitionStatus.DRAFT;
        replaceLines(lines, ids);
    }

    /** A new draft definition (CAT-10 rules checked now and again on publishing). */
    public static TransformationDefinition draft(
            UUID id,
            TenantId tenant,
            String code,
            String name,
            TransformationKind kind,
            @Nullable BigDecimal expectedYieldPct,
            List<LineDraft> lines,
            Supplier<UUID> ids) {
        return new TransformationDefinition(id, tenant, code, name, kind, expectedYieldPct, lines, ids);
    }

    /**
     * Edits the definition. A published one becomes a draft of the next version; the published version stays as it
     * was (inventory keeps it) until this draft is published.
     */
    public void edit(
            @Nullable String newName,
            @Nullable BigDecimal newExpectedYieldPct,
            @Nullable List<LineDraft> newLines,
            Supplier<UUID> ids) {
        requireNotRetired();
        if (newName != null) {
            this.name = newName;
        }
        if (newExpectedYieldPct != null) {
            this.expectedYieldPct = newExpectedYieldPct;
        }
        if (newLines != null) {
            replaceLines(newLines, ids);
        }
        this.status = DefinitionStatus.DRAFT;
    }

    /** Rule CAT-10: publishes the draft as the next immutable version. */
    public void publish(Instant at) {
        requireNotRetired();
        if (status == DefinitionStatus.PUBLISHED) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.DEFINITION_ALREADY_PUBLISHED, Map.of("definitionId", id, "version", defVersion));
        }
        validate(lines.stream().map(TransformationLine::toDraft).toList());
        this.defVersion++;
        this.status = DefinitionStatus.PUBLISHED;
        this.publishedAt = at;
    }

    /** Rule CAT-11: retired, never deleted. Returns whether the status changed. */
    public boolean retire() {
        boolean changed = status != DefinitionStatus.RETIRED;
        this.status = DefinitionStatus.RETIRED;
        return changed;
    }

    /**
     * Rule CAT-14: re-derives the lines sized by packaging units after their sizes changed.
     *
     * @param unitSizes the new {@code qtyInBase} per packaging unit id
     * @return whether any line changed
     */
    public boolean resizeLines(Map<UUID, BigDecimal> unitSizes) {
        if (status == DefinitionStatus.RETIRED) {
            return false;
        }
        boolean changed = false;
        for (TransformationLine line : lines) {
            UUID unitId = line.packagingUnitId();
            BigDecimal size = unitId != null ? unitSizes.get(unitId) : null;
            if (size != null && line.resize(size)) {
                changed = true;
            }
        }
        if (changed && status == DefinitionStatus.PUBLISHED) {
            this.status = DefinitionStatus.DRAFT;
        }
        return changed;
    }

    /** Whether any line refers to the variant. */
    public boolean uses(UUID variantId) {
        return lines.stream().anyMatch(l -> l.variantId().equals(variantId));
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

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public TransformationKind kind() {
        return kind;
    }

    public @Nullable BigDecimal expectedYieldPct() {
        return expectedYieldPct;
    }

    public int defVersion() {
        return defVersion;
    }

    public DefinitionStatus status() {
        return status;
    }

    public @Nullable Instant publishedAt() {
        return publishedAt;
    }

    public List<TransformationLine> lines() {
        return List.copyOf(lines);
    }

    public long version() {
        return version != null ? version : 0;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }

    private void replaceLines(List<LineDraft> drafts, Supplier<UUID> ids) {
        List<LineDraft> valid = validate(drafts);
        lines.clear();
        int lineNo = 1;
        for (LineDraft draft : valid) {
            lines.add(new TransformationLine(ids.get(), tenantId, lineNo++, draft));
        }
    }

    /** Rule CAT-10; returns the lines with the single OUT line's cost share defaulted to 100. */
    private static List<LineDraft> validate(List<LineDraft> drafts) {
        List<LineDraft> outs =
                drafts.stream().filter(l -> l.direction() == Direction.OUT).toList();
        boolean hasIn = drafts.stream().anyMatch(l -> l.direction() == Direction.IN);
        if (!hasIn || outs.isEmpty() || drafts.size() > MAX_LINES) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.DEFINITION_LINES_INVALID,
                    "A definition needs at least one IN and one OUT line",
                    Map.of("lines", drafts.size(), "outLines", outs.size()));
        }
        List<LineDraft> valid = new ArrayList<>();
        BigDecimal shares = BigDecimal.ZERO;
        for (LineDraft draft : drafts) {
            Variant.normalizedQty(draft.qtyBase(), draft.direction() + " " + draft.variantId());
            LineDraft line = draft;
            if (draft.direction() == Direction.OUT) {
                BigDecimal share = draft.costSharePct();
                if (share == null && outs.size() == 1) {
                    share = HUNDRED;
                    line = draft.withCostShare(HUNDRED);
                }
                if (share == null || share.signum() < 0) {
                    throw costShareInvalid(shares);
                }
                shares = shares.add(share);
            }
            valid.add(line);
        }
        if (shares.compareTo(HUNDRED) != 0) {
            throw costShareInvalid(shares);
        }
        return valid;
    }

    private static BusinessRuleViolation costShareInvalid(BigDecimal sum) {
        return new BusinessRuleViolation(
                CatalogErrorCode.COST_SHARE_INVALID,
                "The OUT lines' cost shares must sum to 100",
                Map.of("sum", sum.toPlainString()));
    }

    private void requireNotRetired() {
        if (status == DefinitionStatus.RETIRED) {
            throw new BusinessRuleViolation(CatalogErrorCode.DEFINITION_RETIRED, Map.of("definitionId", id));
        }
    }
}
