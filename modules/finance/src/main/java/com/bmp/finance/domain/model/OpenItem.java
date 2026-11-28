package com.bmp.finance.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An open item of a party's account (FIN-07). The amount is signed: positive is owed (by a customer to us, by us to a
 * supplier), negative is a credit not yet applied. {@code settled} moves towards the amount; the item is settled when
 * nothing remains.
 */
public final class OpenItem {

    private final UUID id;
    private final PartyRef party;
    private final DocumentType documentType;
    private final UUID documentId;
    private final BigDecimal amount;
    private BigDecimal settled;
    private final LocalDate documentDate;
    private final LocalDate dueDate;

    public OpenItem(
            UUID id,
            PartyRef party,
            DocumentType documentType,
            UUID documentId,
            BigDecimal amount,
            BigDecimal settled,
            LocalDate documentDate,
            LocalDate dueDate) {
        this.id = Objects.requireNonNull(id, "id");
        this.party = Objects.requireNonNull(party, "party");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.settled = Objects.requireNonNull(settled, "settled");
        this.documentDate = Objects.requireNonNull(documentDate, "documentDate");
        this.dueDate = Objects.requireNonNull(dueDate, "dueDate");
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("An open item has an amount");
        }
    }

    /** A new item, nothing settled yet. */
    public static OpenItem open(
            UUID id,
            PartyRef party,
            DocumentType type,
            UUID documentId,
            BigDecimal amount,
            LocalDate documentDate,
            LocalDate dueDate) {
        return new OpenItem(id, party, type, documentId, amount, BigDecimal.ZERO, documentDate, dueDate);
    }

    /** What is still open, signed like the amount. */
    public BigDecimal remaining() {
        return amount.subtract(settled);
    }

    public boolean isDebit() {
        return amount.signum() > 0;
    }

    public boolean isOpen() {
        return remaining().signum() != 0;
    }

    /** Takes {@code magnitude} (positive, at most what remains) off what is open. */
    public void settle(BigDecimal magnitude) {
        if (magnitude.signum() <= 0 || magnitude.compareTo(remaining().abs()) > 0) {
            throw new IllegalArgumentException("A settlement is positive and at most what remains");
        }
        settled = settled.add(isDebit() ? magnitude : magnitude.negate());
    }

    /** Days past the due date on {@code asOf}; zero or negative when not yet due. */
    public long daysOverdue(LocalDate asOf) {
        return java.time.temporal.ChronoUnit.DAYS.between(dueDate, asOf);
    }

    public UUID id() {
        return id;
    }

    public PartyRef party() {
        return party;
    }

    public DocumentType documentType() {
        return documentType;
    }

    public UUID documentId() {
        return documentId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public BigDecimal settled() {
        return settled;
    }

    public LocalDate documentDate() {
        return documentDate;
    }

    public LocalDate dueDate() {
        return dueDate;
    }
}
