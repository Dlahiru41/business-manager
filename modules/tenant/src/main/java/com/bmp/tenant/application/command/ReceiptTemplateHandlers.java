package com.bmp.tenant.application.command;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.VersionConflict;
import com.bmp.shared.id.IdGenerator;
import com.bmp.tenant.application.query.ReceiptTemplateView;
import com.bmp.tenant.domain.error.TenantErrorCode;
import com.bmp.tenant.domain.model.ReceiptTemplate;
import com.bmp.tenant.domain.model.ReceiptTemplateId;
import com.bmp.tenant.domain.port.ReceiptTemplateRepository;
import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Receipt templates; at most one per tenant is the default, so marking a new default unsets the old one first. */
@Service
public class ReceiptTemplateHandlers {

    private final ReceiptTemplateRepository templates;
    private final IdGenerator ids;

    public ReceiptTemplateHandlers(ReceiptTemplateRepository templates, IdGenerator ids) {
        this.templates = templates;
        this.ids = ids;
    }

    /** New template. */
    public record CreateTemplate(ReceiptTemplate.Layout layout, boolean isDefault) {}

    /**
     * PATCH semantics: {@code null} fields stay unchanged.
     *
     * @param expectedVersion from {@code If-Match}
     */
    public record UpdateTemplate(
            UUID id,
            long expectedVersion,
            @Nullable String name,
            @Nullable Integer paperWidthMm,
            @Nullable String header,
            @Nullable String footer,
            @Nullable String fields,
            @Nullable String language,
            @Nullable Boolean isDefault) {}

    @Transactional
    @Observed(name = "bmp.tenant.create-receipt-template")
    public ReceiptTemplateView create(CreateTemplate command) {
        if (command.isDefault()) {
            unsetCurrentDefault(null);
        }
        ReceiptTemplate template = new ReceiptTemplate(
                ReceiptTemplateId.of(ids.newUuid()), TenantContext.require(), command.layout(), command.isDefault());
        return ReceiptTemplateView.of(templates.saveAndFlush(template));
    }

    @Transactional
    @Observed(name = "bmp.tenant.update-receipt-template")
    public ReceiptTemplateView update(UpdateTemplate command) {
        ReceiptTemplate template = templates
                .findById(command.id())
                .orElseThrow(() -> new NotFoundException(
                        TenantErrorCode.RECEIPT_TEMPLATE_NOT_FOUND, Map.of("templateId", command.id())));
        if (template.version() != command.expectedVersion()) {
            throw new VersionConflict(template.version());
        }
        ReceiptTemplate.Layout current = template.layout();
        template.changeLayout(new ReceiptTemplate.Layout(
                Objects.requireNonNullElse(command.name(), current.name()),
                Objects.requireNonNullElse(command.paperWidthMm(), current.paperWidthMm()),
                Objects.requireNonNullElse(command.header(), current.header()),
                Objects.requireNonNullElse(command.footer(), current.footer()),
                Objects.requireNonNullElse(command.fields(), current.fields()),
                Objects.requireNonNullElse(command.language(), current.language())));
        if (Boolean.TRUE.equals(command.isDefault()) && !template.isDefault()) {
            unsetCurrentDefault(template.id());
            template.markDefault();
        } else if (Boolean.FALSE.equals(command.isDefault())) {
            template.unmarkDefault();
        }
        return ReceiptTemplateView.of(templates.saveAndFlush(template));
    }

    /** Flushes at once: the unique index allows a single default per tenant at every statement. */
    private void unsetCurrentDefault(@Nullable ReceiptTemplateId keep) {
        templates.findDefault().filter(current -> !current.id().equals(keep)).ifPresent(current -> {
            current.unmarkDefault();
            templates.saveAndFlush(current);
        });
    }
}
