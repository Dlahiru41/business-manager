package com.bmp.inventory.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A batch of a variant (INV-14). */
public record BatchInfo(
        UUID id,
        UUID variantId,
        String code,
        Instant receivedAt,
        @Nullable LocalDate expiresOn) {}
