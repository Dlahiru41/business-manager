package com.bmp.finance.domain.model;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.shared.audit.Auditable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account of the chart (FIN-01). Accounts with a {@link SystemKey} are seeded per tenant and carry the posting
 * rules: they may be renamed and renumbered, but keep their type and stay active.
 */
@Entity
@Table(schema = "finance", name = "account")
public class Account implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    private @Nullable String subtype;

    @Column(name = "parent_id")
    private @Nullable UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "system_key", updatable = false)
    private @Nullable SystemKey systemKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    protected Account() {}

    /** A seeded account of the default chart. */
    public static Account seeded(UUID id, TenantId tenant, SystemKey key) {
        Account account = create(id, tenant, key.defaultCode(), key.defaultName(), key.type(), null, null);
        account.systemKey = key;
        return account;
    }

    /** An account the tenant adds. */
    public static Account create(
            UUID id,
            TenantId tenant,
            String code,
            String name,
            AccountType type,
            @Nullable String subtype,
            @Nullable UUID parentId) {
        Account account = new Account();
        account.id = Objects.requireNonNull(id, "id");
        account.tenantId = tenant.value();
        account.code = Objects.requireNonNull(code, "code");
        account.name = Objects.requireNonNull(name, "name");
        account.type = Objects.requireNonNull(type, "type");
        account.subtype = subtype;
        account.parentId = parentId;
        return account;
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "name");
    }

    public void renumber(String newCode) {
        this.code = Objects.requireNonNull(newCode, "code");
    }

    public void classify(@Nullable String newSubtype) {
        this.subtype = newSubtype;
    }

    /** Rule FIN-01: a system account keeps its type. */
    public void changeType(AccountType newType) {
        if (newType != type && systemKey != null) {
            throw locked("type");
        }
        this.type = newType;
    }

    /** Rule FIN-01: a system account stays active (it is never "deleted"). */
    public void changeStatus(AccountStatus newStatus) {
        if (newStatus == AccountStatus.INACTIVE && systemKey != null) {
            throw locked("status");
        }
        this.status = newStatus;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    private BusinessRuleViolation locked(String field) {
        return new BusinessRuleViolation(
                FinanceErrorCode.SYSTEM_ACCOUNT_LOCKED,
                Map.of("accountId", id, "systemKey", String.valueOf(systemKey), "field", field));
    }

    @Override
    public void recordCreation(Instant at, @Nullable UserId by) {
        audit.created(at, by);
    }

    @Override
    public void recordModification(Instant at, @Nullable UserId by) {
        audit.modified(at, by);
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public AccountType type() {
        return type;
    }

    public @Nullable String subtype() {
        return subtype;
    }

    public @Nullable UUID parentId() {
        return parentId;
    }

    public @Nullable SystemKey systemKey() {
        return systemKey;
    }

    public AccountStatus status() {
        return status;
    }

    public long version() {
        return version == null ? 0 : version;
    }

    public AuditStamps audit() {
        return audit;
    }
}
