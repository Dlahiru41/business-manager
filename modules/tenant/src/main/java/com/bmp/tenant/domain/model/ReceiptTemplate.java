package com.bmp.tenant.domain.model;

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
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A receipt layout used by the print agent (aggregate root). At most one template per tenant is the default; making
 * one the default is coordinated by the handler, which unsets the previous default first.
 */
@Entity
@Table(schema = "tenant", name = "receipt_template")
public class ReceiptTemplate implements Auditable {

    /** Supported paper widths in millimetres (58 mm and 80 mm thermal rolls). */
    public static final Set<Integer> PAPER_WIDTHS_MM = Set.of(58, 80);

    /** Supported receipt languages. */
    public static final Set<String> LANGUAGES = Set.of("en", "si", "ta");

    private static final String EMPTY_JSON_OBJECT = "{}";

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "paper_width_mm", nullable = false)
    private int paperWidthMm;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String header;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String footer;

    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String fields;

    @Column(nullable = false)
    private String language;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Version
    private long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    @SuppressWarnings("NullAway.Init") // JPA
    protected ReceiptTemplate() {}

    public ReceiptTemplate(ReceiptTemplateId id, TenantId tenant, Layout layout, boolean isDefault) {
        this.id = id.value();
        this.tenantId = tenant.value();
        this.name = layout.name();
        this.paperWidthMm = layout.paperWidthMm();
        this.header = layout.header();
        this.footer = layout.footer();
        this.fields = layout.fields();
        this.language = layout.language();
        this.isDefault = isDefault;
    }

    public void changeLayout(Layout layout) {
        this.name = layout.name();
        this.paperWidthMm = layout.paperWidthMm();
        this.header = layout.header();
        this.footer = layout.footer();
        this.fields = layout.fields();
        this.language = layout.language();
    }

    public void markDefault() {
        this.isDefault = true;
    }

    public void unmarkDefault() {
        this.isDefault = false;
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public ReceiptTemplateId id() {
        return ReceiptTemplateId.of(id);
    }

    public Layout layout() {
        return new Layout(name, paperWidthMm, header, footer, fields, language);
    }

    public boolean isDefault() {
        return isDefault;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return audit.createdAt();
    }

    /**
     * What a template prints.
     *
     * @param name template name, unique per tenant (case-insensitive)
     * @param paperWidthMm 58 or 80
     * @param header JSON object
     * @param footer JSON object
     * @param fields JSON object of field switches
     * @param language en, si or ta
     */
    public record Layout(String name, int paperWidthMm, String header, String footer, String fields, String language) {
        public Layout {
            Objects.requireNonNull(name, "name");
            header = header == null || header.isBlank() ? EMPTY_JSON_OBJECT : header;
            footer = footer == null || footer.isBlank() ? EMPTY_JSON_OBJECT : footer;
            fields = fields == null || fields.isBlank() ? EMPTY_JSON_OBJECT : fields;
            if (!PAPER_WIDTHS_MM.contains(paperWidthMm)) {
                throw new IllegalArgumentException("Unsupported paper width: " + paperWidthMm);
            }
            if (!LANGUAGES.contains(language)) {
                throw new IllegalArgumentException("Unsupported language: " + language);
            }
        }
    }
}
