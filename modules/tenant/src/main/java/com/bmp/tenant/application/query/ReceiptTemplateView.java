package com.bmp.tenant.application.query;

import com.bmp.tenant.domain.model.ReceiptTemplate;
import java.time.Instant;
import java.util.UUID;

/** Read model of a receipt template; header, footer and fields are JSON objects. */
public record ReceiptTemplateView(
        UUID id,
        String name,
        int paperWidthMm,
        String header,
        String footer,
        String fields,
        String language,
        boolean isDefault,
        long version,
        Instant createdAt) {

    public static ReceiptTemplateView of(ReceiptTemplate template) {
        var layout = template.layout();
        return new ReceiptTemplateView(
                template.id().value(),
                layout.name(),
                layout.paperWidthMm(),
                layout.header(),
                layout.footer(),
                layout.fields(),
                layout.language(),
                template.isDefault(),
                template.version(),
                template.createdAt());
    }
}
