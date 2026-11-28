package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.AccountStatus;
import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.SystemKey;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The chart of accounts of the current tenant. */
public interface AccountRepository {

    Optional<Account> findById(UUID id);

    List<Account> findAllById(Collection<UUID> ids);

    List<Account> findBySystemKeys(Collection<SystemKey> keys);

    boolean hasSystemAccounts();

    /** Accounts by code, after {@code afterCode}. */
    List<Account> page(
            @Nullable AccountType type, @Nullable AccountStatus status, @Nullable String afterCode, int limit);

    Account save(Account account);
}
