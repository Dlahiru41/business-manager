package com.bmp.printagent.receipt;

import com.bmp.receipt.ReceiptModel;
import com.bmp.receipt.ReceiptTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Receipts and templates in the shapes of documents.yaml ({@code ReceiptModel}, {@code ReceiptTemplate}): amounts as
 * strings, the receipt's time local to the shop. The local time is carried as if it were UTC and printed in UTC, so it
 * prints as the till sent it whatever the PC's time zone.
 */
public final class ReceiptJson {

    /** The zone receipts are read and printed in (see the class comment). */
    public static final ZoneId PRINT_ZONE = ZoneOffset.UTC;

    private ReceiptJson() {}

    /** A receipt; {@link IllegalArgumentException} names what is missing or malformed. */
    public static ReceiptModel receipt(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("A receipt is a JSON object");
        }
        List<ReceiptModel.Line> lines = new ArrayList<>();
        for (JsonNode line : array(node, "lines", true)) {
            lines.add(new ReceiptModel.Line(
                    text(line, "name"),
                    amount(line, "qty"),
                    Objects.requireNonNullElse(optionalText(line, "unit"), ""),
                    amount(line, "unitPrice"),
                    amount(line, "lineTotal"),
                    optionalAmount(line, "discount")));
        }
        List<ReceiptModel.Tender> tenders = new ArrayList<>();
        for (JsonNode tender : array(node, "tenders", false)) {
            tenders.add(new ReceiptModel.Tender(text(tender, "method"), amount(tender, "amount")));
        }
        return new ReceiptModel(
                uuid(node, "billId"),
                text(node, "billNumber"),
                issuedAt(text(node, "issuedAt")),
                optionalText(node, "cashier"),
                optionalText(node, "register"),
                lines,
                optionalAmount(node, "discountTotal"),
                optionalAmount(node, "taxTotal"),
                amount(node, "total"),
                tenders,
                optionalAmount(node, "change"),
                optionalText(node, "customer"),
                text(node, "currency"),
                node.path("copy").asBoolean(false),
                node.path("cashTendered").asBoolean(false));
    }

    /** A template as {@code GET /api/v1/print-agents/{id}/template} answers it. */
    public static ReceiptTemplate template(JsonNode node) {
        if (!node.isObject() || !node.path("version").canConvertToLong()) {
            throw new IllegalArgumentException("A template is a JSON object with a version");
        }
        return new ReceiptTemplate(
                node.path("version").asLong(),
                text(node, "shopName"),
                strings(node, "addressLines"),
                optionalText(node, "phone"),
                optionalText(node, "taxNumber"),
                strings(node, "footerLines"),
                node.path("paperWidthMm").asInt(),
                node.path("showQr").asBoolean(false),
                node.path("openDrawerOnCash").asBoolean(true),
                node.path("language").isString() ? node.path("language").asString() : "en",
                PRINT_ZONE);
    }

    private static Instant issuedAt(String value) {
        try {
            return LocalDateTime.parse(value).atZone(PRINT_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("issuedAt is a local date and time, e.g. 2026-11-25T14:30:00", e);
        }
    }

    private static Iterable<JsonNode> array(JsonNode node, String field, boolean required) {
        JsonNode value = node.path(field);
        if (value.isArray()) {
            return value;
        }
        if (required || (!value.isMissingNode() && !value.isNull())) {
            throw new IllegalArgumentException(field + " is a list");
        }
        return List.of();
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node, field, false)) {
            values.add(value.asString());
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static @Nullable String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is a UUID", e);
        }
    }

    private static BigDecimal amount(JsonNode node, String field) {
        try {
            return new BigDecimal(text(node, field));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " is a decimal number as a string", e);
        }
    }

    private static BigDecimal optionalAmount(JsonNode node, String field) {
        return optionalText(node, field) == null ? BigDecimal.ZERO : amount(node, field);
    }
}
