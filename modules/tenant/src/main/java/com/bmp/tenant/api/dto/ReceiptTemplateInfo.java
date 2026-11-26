package com.bmp.tenant.api.dto;

import java.util.UUID;

/**
 * The tenant's default receipt template (DOC-06) as other modules read it.
 *
 * @param header JSON object, {@code {"lines": [...]}}: the shop name first, then address lines
 * @param footer JSON object, {@code {"lines": [...]}}
 * @param fields JSON object of switches, e.g. {@code {"showQr": true, "openDrawerOnCash": true}}
 */
public record ReceiptTemplateInfo(
        UUID id, long version, int paperWidthMm, String header, String footer, String fields, String language) {}
