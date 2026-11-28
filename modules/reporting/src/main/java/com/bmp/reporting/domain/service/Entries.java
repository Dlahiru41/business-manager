package com.bmp.reporting.domain.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The ids of {@code fact_sale_line} entries (ADR-033 §2): a bill line sold keeps its own id; its return on a credit
 * note and its void get name-based ids, so the cost half finds the same entry as the sale half.
 */
public final class Entries {

    private Entries() {}

    public static UUID sold(UUID billLineId) {
        return billLineId;
    }

    public static UUID returned(UUID creditNoteId, UUID billLineId) {
        return UUID.nameUUIDFromBytes(("return:" + creditNoteId + ":" + billLineId).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID voided(UUID billLineId) {
        return UUID.nameUUIDFromBytes(("void:" + billLineId).getBytes(StandardCharsets.UTF_8));
    }

    /** The entry a cost belongs to, by the kind of stock movement. */
    public static UUID ofCost(String kind, UUID sourceId, UUID billLineId) {
        return switch (kind) {
            case "SALE_RETURN" -> returned(sourceId, billLineId);
            case "SALE_VOID" -> voided(billLineId);
            default -> sold(billLineId);
        };
    }
}
