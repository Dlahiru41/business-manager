package com.bmp.sales.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A tender of a completed bill, as captured at the till. */
@Entity
@Table(schema = "sales", name = "bill_tender")
public class BillTender {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TenderMethod method;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(updatable = false)
    private @Nullable BigDecimal tendered;

    @Column(name = "change_given", updatable = false)
    private @Nullable BigDecimal changeGiven;

    @Column(updatable = false)
    private @Nullable String reference;

    @SuppressWarnings("NullAway.Init") // JPA
    protected BillTender() {}

    BillTender(UUID tenantId, Tender tender) {
        this.id = tender.id();
        this.tenantId = tenantId;
        this.method = tender.method();
        this.amount = tender.amount();
        this.reference = tender.reference();
        if (tender instanceof Tender.Cash cash) {
            this.tendered = cash.tendered();
            this.changeGiven = cash.change().max(BigDecimal.ZERO);
        }
    }

    public Tender tender() {
        return Tender.of(id, method, amount, tendered, reference);
    }

    public UUID id() {
        return id;
    }

    public TenderMethod method() {
        return method;
    }

    public BigDecimal amount() {
        return amount;
    }

    public @Nullable BigDecimal tendered() {
        return tendered;
    }

    public @Nullable BigDecimal changeGiven() {
        return changeGiven;
    }

    public @Nullable String reference() {
        return reference;
    }
}
