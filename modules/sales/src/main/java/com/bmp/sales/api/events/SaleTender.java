package com.bmp.sales.api.events;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A tender of a bill; payments captures every one but CREDIT (PAY-01). */
public record SaleTender(
        UUID tenderId,
        String method,
        BigDecimal amount,
        @Nullable BigDecimal tendered,
        @Nullable BigDecimal change,
        @Nullable String reference) {}
