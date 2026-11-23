package com.bmp.party.adapter.out.persistence;

import com.bmp.party.domain.model.Party;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.model.PartyType;
import com.bmp.party.domain.port.PartyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link PartyRepository} on JPA; filters are added only when given, so no query binds an untyped null. */
@Repository
class JpaPartyStore implements PartyRepository {

    private final SpringDataParties parties;
    private final EntityManager em;

    JpaPartyStore(SpringDataParties parties, EntityManager em) {
        this.parties = parties;
        this.em = em;
    }

    @Override
    public Optional<Party> findById(UUID id) {
        return parties.findById(id);
    }

    @Override
    public List<Party> findByPhone(String phone) {
        return parties.findByPhone(phone);
    }

    @Override
    public List<Party> findPage(
            @Nullable PartyType type,
            @Nullable PartyStatus status,
            @Nullable UUID customerGroupId,
            @Nullable UUID beforeId,
            int limit) {
        StringBuilder jpql = new StringBuilder("select p from Party p where 1 = 1");
        if (type != null) {
            jpql.append(" and p.type = :type");
        }
        if (status != null) {
            jpql.append(" and p.status = :status");
        }
        if (customerGroupId != null) {
            jpql.append(" and p.customerGroupId = :groupId");
        }
        if (beforeId != null) {
            jpql.append(" and p.id < :beforeId");
        }
        TypedQuery<Party> query =
                em.createQuery(jpql.append(" order by p.id desc").toString(), Party.class);
        if (type != null) {
            query.setParameter("type", type);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (customerGroupId != null) {
            query.setParameter("groupId", customerGroupId);
        }
        if (beforeId != null) {
            query.setParameter("beforeId", beforeId);
        }
        return query.setMaxResults(limit).getResultList();
    }

    @Override
    public List<Party> search(String wordPrefix, @Nullable String phonePrefix, Collection<PartyType> types, int limit) {
        String start = like(wordPrefix) + "%";
        StringBuilder jpql = new StringBuilder("""
                select p from Party p
                 where p.status = :active and p.type in :types
                   and (lower(p.displayName) like :start escape '!' or lower(p.displayName) like :word escape '!'""");
        if (phonePrefix != null) {
            jpql.append(" or p.phone like :phone escape '!'");
        }
        TypedQuery<Party> query = em.createQuery(
                        jpql.append(") order by p.displayName, p.id").toString(), Party.class)
                .setParameter("active", PartyStatus.ACTIVE)
                .setParameter("types", types)
                .setParameter("start", start)
                .setParameter("word", "% " + start);
        if (phonePrefix != null) {
            query.setParameter("phone", like(phonePrefix) + "%");
        }
        List<Party> found = new ArrayList<>(query.setMaxResults(limit).getResultList());
        // Names that start with the search come first; the rest keep their alphabetical order.
        found.sort(Comparator.comparing(
                p -> !p.displayName().toLowerCase(Locale.ROOT).startsWith(wordPrefix)));
        return found;
    }

    @Override
    public Party saveAndFlush(Party party) {
        return parties.saveAndFlush(party);
    }

    private static String like(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
