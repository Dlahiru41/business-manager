package com.bmp.finance.domain.model;

import com.bmp.shared.audit.Auditable;
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
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An expense category: what an expense was for and the account it posts to (FIN-09). */
@Entity
@Table(schema = "finance", name = "expense_category")
public class ExpenseCategory implements Auditable {

    @Id
    private UUID id;

    @org.hibernate.annotations.TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Version
    private @Nullable Long version;

    @Embedded
    private AuditStamps audit = new AuditStamps();

    protected ExpenseCategory() {}

    public static ExpenseCategory create(UUID id, TenantId tenant, String code, String name, UUID accountId) {
        ExpenseCategory category = new ExpenseCategory();
        category.id = Objects.requireNonNull(id, "id");
        category.tenantId = tenant.value();
        category.code = Objects.requireNonNull(code, "code");
        category.name = Objects.requireNonNull(name, "name");
        category.accountId = Objects.requireNonNull(accountId, "accountId");
        return category;
    }

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "name");
    }

    /** Points the category at another expense account; past expenses keep their journals. */
    public void postTo(UUID newAccountId) {
        this.accountId = Objects.requireNonNull(newAccountId, "accountId");
    }

    public void changeStatus(AccountStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status");
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
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

    public UUID accountId() {
        return accountId;
    }

    public AccountStatus status() {
        return status;
    }

    public long version() {
        return version == null ? 0 : version;
    }
}
