package com.bmp.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** One allowed value of an attribute type (Red, XL, MySoy). Part of the {@link Product} aggregate. */
@Entity
@Table(schema = "catalog", name = "attribute_value")
public class AttributeValue {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String value;

    @Column(nullable = false)
    private int sort;

    @SuppressWarnings("NullAway.Init") // JPA
    protected AttributeValue() {}

    AttributeValue(UUID id, UUID tenantId, String code, String value, int sort) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.code = Objects.requireNonNull(code, "code");
        this.value = Objects.requireNonNull(value, "value");
        this.sort = sort;
    }

    void change(String newValue, int newSort) {
        this.value = Objects.requireNonNull(newValue, "newValue");
        this.sort = newSort;
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String value() {
        return value;
    }

    public int sort() {
        return sort;
    }
}
