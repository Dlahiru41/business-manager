package com.bmp.procurement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.UUID;

/** A posted receipt an invoice pays for, with its value when matched (PRO-05). */
@Embeddable
public record InvoiceMatch(
        @Column(name = "receipt_id", nullable = false) UUID receiptId,
        @Column(name = "receipt_number", nullable = false) String receiptNumber,
        @Column(name = "amount", nullable = false) BigDecimal receiptValue) {}
