package com.bmp.finance.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one item took off another (FIN-07): {@code amount} (positive) came off both the debit and the credit item.
 *
 * @param itemId the item that arrived and settles
 * @param againstItemId the open item it settled
 */
public record Settlement(UUID itemId, UUID againstItemId, BigDecimal amount) {}
