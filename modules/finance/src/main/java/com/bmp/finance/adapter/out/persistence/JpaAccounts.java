package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.AccountStatus;
import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.finance.domain.port.AccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link AccountRepository} on JPA; Hibernate's tenant filter and row-level security keep it to one tenant. */
@Repository
class JpaAccounts implements AccountRepository {

    private final SpringDataAccounts accounts;
    private final EntityManager em;

    JpaAccounts(SpringDataAccounts accounts, EntityManager em) {
        this.accounts = accounts;
        this.em = em;
    }

    @Override
    public Optional<Account> findById(UUID id) {
        return accounts.findById(id);
    }

    @Override
    public List<Account> findAllById(Collection<UUID> ids) {
        return accounts.findAllById(ids);
    }

    @Override
    public List<Account> findBySystemKeys(Collection<SystemKey> keys) {
        return accounts.findBySystemKeys(keys);
    }

    @Override
    public boolean hasSystemAccounts() {
        return accounts.hasSystemAccounts();
    }

    @Override
    public List<Account> page(
            @Nullable AccountType type, @Nullable AccountStatus status, @Nullable String afterCode, int limit) {
        StringBuilder jpql = new StringBuilder("select a from Account a where 1 = 1");
        if (type != null) {
            jpql.append(" and a.type = :type");
        }
        if (status != null) {
            jpql.append(" and a.status = :status");
        }
        if (afterCode != null) {
            jpql.append(" and a.code > :after");
        }
        TypedQuery<Account> query = em.createQuery(
                        jpql.append(" order by a.code").toString(), Account.class)
                .setMaxResults(limit);
        if (type != null) {
            query.setParameter("type", type);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (afterCode != null) {
            query.setParameter("after", afterCode);
        }
        return query.getResultList();
    }

    @Override
    public Account save(Account account) {
        return accounts.saveAndFlush(account);
    }
}
