package com.bmp.test.fixture;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A till of the pilot shop working offline: it numbers its bills, prices them as the till would and keeps them as
 * sync upload records ({@code sync.yaml} {@code UploadRecord}) until {@link #batch()} sends them as one upload.
 */
public final class PosSimulator {

    private final PilotShop shop;
    private final String prefix;
    private final @Nullable UUID shift;
    private final List<String> records = new ArrayList<>();
    private long number;

    /**
     * A till whose next bill is {@code prefix-<next>}, selling in the shift it opened before it went offline; without
     * one its bills go to the register's offline shift (flagged {@code OFFLINE_SHIFT}).
     */
    public PosSimulator(PilotShop shop, String prefix, long next, @Nullable UUID shift) {
        this.shop = shop;
        this.prefix = prefix;
        this.number = next - 1;
        this.shift = shift;
    }

    /** A line of a bill the till makes. */
    public record Line(String sku, String unit, String qty, String unitPrice, String lineDiscount) {

        /** A line without a discount. */
        public static Line of(String sku, String unit, String qty, String unitPrice) {
            return new Line(sku, unit, qty, unitPrice, "0.00");
        }

        BigDecimal gross() {
            return new BigDecimal(qty).multiply(new BigDecimal(unitPrice)).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal total() {
            return gross().subtract(new BigDecimal(lineDiscount));
        }
    }

    /** A bill the till kept, with what the test needs to check it later. */
    public record Bill(UUID id, String number, UUID lineId, BigDecimal total, String method) {}

    /** The next number is skipped, as when a till loses a bill (SYN-06). */
    public String skip() {
        number++;
        return number(number);
    }

    /** A bill paid in cash, exactly. */
    public Bill cash(Instant at, Line... lines) {
        return bill(at, null, "CASH", List.of(lines));
    }

    /** A bill on the customer's account (SAL-07). */
    public Bill credit(Instant at, UUID customer, Line... lines) {
        return bill(at, customer, "CREDIT", List.of(lines));
    }

    /** All bills kept so far, as one upload request body. */
    public String batch() {
        return "{\"records\": [" + String.join(", ", records) + "]}";
    }

    public int size() {
        return records.size();
    }

    private Bill bill(Instant at, @Nullable UUID customer, String method, List<Line> lines) {
        UUID id = UUID.randomUUID();
        String billNumber = number(++number);
        List<UUID> lineIds = new ArrayList<>();
        String lineJson = lines.stream()
                .map(l -> {
                    UUID lineId = UUID.randomUUID();
                    lineIds.add(lineId);
                    JsonNode variant = shop.variant(l.sku());
                    return """
                            {"id": "%s", "variantId": "%s", "packagingUnitId": "%s", "qty": "%s", "unitPrice": "%s"%s}""".formatted(
                                    lineId,
                                    variant.path("id").asString(),
                                    PilotShop.unit(variant, l.unit()),
                                    l.qty(),
                                    l.unitPrice(),
                                    new BigDecimal(l.lineDiscount()).signum() == 0
                                            ? ""
                                            : ", \"lineDiscount\": \"" + l.lineDiscount() + "\"");
                })
                .collect(Collectors.joining(", "));
        BigDecimal gross = lines.stream().map(Line::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = lines.stream().map(Line::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        String tender = "CASH".equals(method)
                ? "{\"id\": \"%s\", \"method\": \"CASH\", \"amount\": \"%s\", \"tendered\": \"%s\"}"
                        .formatted(UUID.randomUUID(), total.toPlainString(), total.toPlainString())
                : "{\"id\": \"%s\", \"method\": \"%s\", \"amount\": \"%s\"}"
                        .formatted(UUID.randomUUID(), method, total.toPlainString());
        String payload = """
                {"id": "%s", "number": "%s"%s, "channel": "RETAIL"%s, "lines": [%s], "tenders": [%s],
                 "totals": {"subtotal": "%s", "discountTotal": "%s", "taxTotal": "0.00", "grandTotal": "%s"},
                 "completedAt": "%s"}""".formatted(
                        id,
                        billNumber,
                        shift == null ? "" : ", \"shiftId\": \"" + shift + "\"",
                        customer == null ? "" : ", \"customerId\": \"" + customer + "\"",
                        lineJson,
                        tender,
                        gross.toPlainString(),
                        gross.subtract(total).toPlainString(),
                        total.toPlainString(),
                        at);
        records.add("{\"type\": \"BILL\", \"id\": \"" + id + "\", \"payload\": " + payload + "}");
        return new Bill(id, billNumber, Objects.requireNonNull(lineIds.getFirst()), total, method);
    }

    private String number(long n) {
        return prefix + "-%06d".formatted(n);
    }
}
