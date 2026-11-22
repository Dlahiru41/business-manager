package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.TaxCategory;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.pricing.domain.port.TaxCategoryRepository;
import com.bmp.pricing.domain.port.TaxRateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Tax categories and rates on Spring Data JPA. */
@Repository
class JpaTaxStore implements TaxCategoryRepository, TaxRateRepository {

    private final SpringDataTaxCategories categories;
    private final SpringDataTaxRates rates;
    private final EntityManager em;

    JpaTaxStore(SpringDataTaxCategories categories, SpringDataTaxRates rates, EntityManager em) {
        this.categories = categories;
        this.rates = rates;
        this.em = em;
    }

    @Override
    public Optional<TaxCategory> findById(UUID id) {
        return categories.findById(id);
    }

    @Override
    public List<TaxCategory> findPage(@Nullable String afterCode, int limit) {
        var query = afterCode == null
                ? em.createQuery("select c from TaxCategory c order by c.code", TaxCategory.class)
                : em.createQuery("select c from TaxCategory c where c.code > :after order by c.code", TaxCategory.class)
                        .setParameter("after", afterCode);
        return query.setMaxResults(limit).getResultList();
    }

    @Override
    public TaxCategory saveAndFlush(TaxCategory category) {
        return categories.saveAndFlush(category);
    }

    @Override
    public Optional<TaxRate> findRate(UUID id) {
        return rates.findById(id);
    }

    @Override
    public List<TaxRate> findByCategories(Collection<UUID> taxCategoryIds) {
        if (taxCategoryIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery(
                        "select r from TaxRate r where r.taxCategoryId in :categories order by r.validFrom, r.code",
                        TaxRate.class)
                .setParameter("categories", taxCategoryIds)
                .getResultList();
    }

    @Override
    public List<TaxRate> findPage(@Nullable UUID taxCategoryId, @Nullable UUID afterId, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TaxRate> query = cb.createQuery(TaxRate.class);
        Root<TaxRate> rate = query.from(TaxRate.class);
        List<Predicate> where = new ArrayList<>();
        if (taxCategoryId != null) {
            where.add(cb.equal(rate.get("taxCategoryId"), taxCategoryId));
        }
        if (afterId != null) {
            where.add(cb.greaterThan(rate.get("id"), afterId));
        }
        query.select(rate).where(where.toArray(Predicate[]::new)).orderBy(cb.asc(rate.get("id")));
        return em.createQuery(query).setMaxResults(limit).getResultList();
    }

    @Override
    public TaxRate saveAndFlush(TaxRate rate) {
        return rates.saveAndFlush(rate);
    }
}
