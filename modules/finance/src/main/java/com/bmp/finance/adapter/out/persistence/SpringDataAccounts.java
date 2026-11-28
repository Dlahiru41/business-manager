package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.SystemKey;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SpringDataAccounts extends JpaRepository<Account, UUID> {

    @Query("select a from Account a where a.systemKey in :keys")
    List<Account> findBySystemKeys(Collection<SystemKey> keys);

    @Query("select count(a) > 0 from Account a where a.systemKey is not null")
    boolean hasSystemAccounts();
}
