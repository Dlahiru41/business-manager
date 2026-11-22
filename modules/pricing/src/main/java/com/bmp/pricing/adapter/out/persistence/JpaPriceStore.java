package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.port.PriceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link PriceRepository} on Spring Data JPA. */
@Repository
class JpaPriceStore implements PriceRepository {

    private final SpringDataPrices prices;
    private final EntityManager em;

    JpaPriceStore(SpringDataPrices prices, EntityManager em) {
        this.prices = prices;
        this.em = em;
    }

    @Override
    public List<Price> findByListsAndUnits(Collection<UUID> listIds, Collection<UUID> unitIds) {
        if (listIds.isEmpty() || unitIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery(
                        "select p from Price p where p.priceListId in :lists and p.packagingUnitId in :units",
                        Price.class)
                .setParameter("lists", listIds)
                .setParameter("units", unitIds)
                .getResultList();
    }

    @Override
    public List<Price> findPage(
            UUID listId,
            @Nullable UUID variantId,
            @Nullable UUID unitId,
            @Nullable Instant validAt,
            @Nullable UUID afterId,
            int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Price> query = cb.createQuery(Price.class);
        Root<Price> price = query.from(Price.class);
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(price.get("priceListId"), listId));
        if (variantId != null) {
            where.add(cb.equal(price.get("variantId"), variantId));
        }
        if (unitId != null) {
            where.add(cb.equal(price.get("packagingUnitId"), unitId));
        }
        if (validAt != null) {
            where.add(cb.lessThanOrEqualTo(price.get("validFrom"), validAt));
            where.add(cb.or(cb.isNull(price.get("validTo")), cb.greaterThan(price.get("validTo"), validAt)));
        }
        if (afterId != null) {
            where.add(cb.greaterThan(price.get("id"), afterId));
        }
        query.select(price).where(where.toArray(Predicate[]::new)).orderBy(cb.asc(price.get("id")));
        return em.createQuery(query).setMaxResults(limit).getResultList();
    }

    @Override
    public List<Price> findChangedSince(UUID listId, int version) {
        return em.createQuery(
                        "select p from Price p where p.priceListId = :list and p.listVersion > :version order by p.id",
                        Price.class)
                .setParameter("list", listId)
                .setParameter("version", version)
                .getResultList();
    }

    @Override
    public List<Price> findNotEndedAt(UUID listId, Instant at) {
        return em.createQuery("""
                        select p from Price p where p.priceListId = :list and (p.validTo is null or p.validTo > :at)
                         order by p.id
                        """, Price.class)
                .setParameter("list", listId)
                .setParameter("at", at)
                .getResultList();
    }

    @Override
    public void saveAll(Collection<Price> changed) {
        prices.saveAllAndFlush(changed);
    }
}
