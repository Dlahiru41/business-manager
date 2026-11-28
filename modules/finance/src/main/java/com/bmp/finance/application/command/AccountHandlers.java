package com.bmp.finance.application.command;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.AccountStatus;
import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.port.AccountRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.VersionConflict;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The tenant's chart: accounts added, renamed, renumbered, reclassified or deactivated (FIN-01). */
@Service
public class AccountHandlers {

    private final AccountRepository accounts;

    public AccountHandlers(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public record CreateAccount(
            UUID id,
            String code,
            String name,
            AccountType type,
            @Nullable String subtype,
            @Nullable UUID parentId) {}

    public record UpdateAccount(
            UUID id,
            long expectedVersion,
            @Nullable String code,
            @Nullable String name,
            @Nullable AccountType type,
            @Nullable String subtype,
            @Nullable AccountStatus status) {}

    @Transactional
    public Account create(CreateAccount c) {
        if (c.parentId() != null && accounts.findById(c.parentId()).isEmpty()) {
            throw new BusinessRuleViolation(FinanceErrorCode.UNKNOWN_PARENT_ACCOUNT, Map.of("parentId", c.parentId()));
        }
        return accounts.save(Account.create(
                c.id(), TenantContext.require(), c.code(), c.name(), c.type(), c.subtype(), c.parentId()));
    }

    /** Rule FIN-01: system accounts keep their type and stay active. */
    @Transactional
    public Account update(UpdateAccount u) {
        Account account = find(u.id());
        if (account.version() != u.expectedVersion()) {
            throw new VersionConflict(account.version());
        }
        if (u.code() != null) {
            account.renumber(u.code());
        }
        if (u.name() != null) {
            account.rename(u.name());
        }
        if (u.subtype() != null) {
            account.classify(u.subtype());
        }
        if (u.type() != null) {
            account.changeType(u.type());
        }
        if (u.status() != null) {
            account.changeStatus(u.status());
        }
        return accounts.save(account);
    }

    @Transactional(readOnly = true)
    public Account find(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new NotFoundException(FinanceErrorCode.ACCOUNT_NOT_FOUND, Map.of("accountId", id)));
    }
}
