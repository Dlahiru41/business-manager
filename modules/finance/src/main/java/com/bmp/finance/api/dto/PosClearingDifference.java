package com.bmp.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A walk-in bill whose POS clearing does not net to zero, since {@code since}. */
public record PosClearingDifference(UUID billId, BigDecimal amount, LocalDate since) {}
