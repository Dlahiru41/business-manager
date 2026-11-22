package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.CatalogStatus;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.PageKey;
import com.bmp.catalog.domain.port.VariantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link VariantRepository} on Spring Data JPA; RLS and {@code @TenantId} scope every query to the tenant. */
@Repository
class JpaVariantStore implements VariantRepository {

    private final SpringDataVariants variants;
    private final EntityManager em;

    JpaVariantStore(SpringDataVariants variants, EntityManager em) {
        this.variants = variants;
        this.em = em;
    }

    @Override
    public Optional<Variant> findById(UUID id) {
        return variants.findById(id);
    }

    @Override
    public List<Variant> findAllById(Collection<UUID> ids) {
        return ids.isEmpty() ? List.of() : variants.findAllById(ids);
    }

    @Override
    public Optional<Variant> findByPackagingUnit(UUID packagingUnitId) {
        return em.createQuery("select v from Variant v join v.packagingUnits u where u.id = :unitId", Variant.class)
                .setParameter("unitId", packagingUnitId)
                .getResultStream()
                .findFirst();
    }

    @Override
    public Optional<Variant> findBySku(String sku) {
        return em.createQuery("select v from Variant v where v.sku = :sku", Variant.class)
                .setParameter("sku", sku)
                .getResultStream()
                .findFirst();
    }

    @Override
    public Optional<Variant> findByBarcode(String barcode) {
        return em.createQuery(
                        "select v from Variant v join v.packagingUnits u join u.barcodes b where b = :barcode",
                        Variant.class)
                .setParameter("barcode", barcode)
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<Variant> findByProduct(UUID productId) {
        return em.createQuery(
                        "select v from Variant v where v.productId = :productId order by v.audit.createdAt, v.id",
                        Variant.class)
                .setParameter("productId", productId)
                .getResultList();
    }

    @Override
    public boolean existsByProduct(UUID productId) {
        return !em.createQuery("select v.id from Variant v where v.productId = :productId", UUID.class)
                .setParameter("productId", productId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Override
    public Set<String> skusStartingWith(String prefix) {
        return new HashSet<>(
                em.createQuery("select v.sku from Variant v where v.sku like :prefix escape '\\'", String.class)
                        .setParameter("prefix", Keysets.startsWith(prefix))
                        .getResultList());
    }

    @Override
    public List<Variant> findPage(Filter filter, @Nullable PageKey after, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Variant> query = cb.createQuery(Variant.class);
        Root<Variant> variant = query.from(Variant.class);
        List<Predicate> where = new ArrayList<>();
        String q = filter.q();
        if (q != null) {
            String pattern = Keysets.contains(q);
            where.add(cb.or(
                    cb.like(cb.lower(variant.get("sku")), pattern, Keysets.ESCAPE),
                    cb.like(cb.lower(variant.get("name")), pattern, Keysets.ESCAPE)));
        }
        if (filter.productId() != null) {
            where.add(cb.equal(variant.get("productId"), filter.productId()));
        }
        if (filter.status() != null) {
            where.add(cb.equal(variant.get("status"), filter.status()));
        }
        Boolean sellable = filter.sellable();
        if (sellable != null) {
            where.add(flag(cb, query, variant, "canSell", sellable));
        }
        Boolean purchasable = filter.purchasable();
        if (purchasable != null) {
            where.add(flag(cb, query, variant, "canBuy", purchasable));
        }
        if (after != null) {
            where.add(Keysets.after(cb, variant.get("sku"), variant.get("id"), after));
        }
        query.select(variant)
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.asc(variant.get("sku")), cb.asc(variant.get("id")));
        return em.createQuery(query).setMaxResults(limit).getResultList();
    }

    @Override
    public List<Variant> findChangedSince(long revision) {
        return em.createQuery(
                        "select v from Variant v where v.revision > :revision order by v.revision, v.id", Variant.class)
                .setParameter("revision", revision)
                .getResultList();
    }

    @Override
    public Variant save(Variant variant) {
        return variants.save(variant);
    }

    @Override
    public Variant saveAndFlush(Variant variant) {
        return variants.saveAndFlush(variant);
    }

    /** Rule CAT-08: the variant is active and has an active unit with the flag (or, for {@code false}, not). */
    private static Predicate flag(
            CriteriaBuilder cb, CriteriaQuery<?> query, Root<Variant> variant, String unitFlag, boolean wanted) {
        Subquery<UUID> units = query.subquery(UUID.class);
        Root<Variant> correlated = units.correlate(variant);
        Join<Variant, PackagingUnit> unit = correlated.join("packagingUnits");
        units.select(unit.get("id"))
                .where(cb.equal(unit.get("status"), CatalogStatus.ACTIVE), cb.isTrue(unit.get(unitFlag)));
        Predicate allowed = cb.and(cb.equal(variant.get("status"), CatalogStatus.ACTIVE), cb.exists(units));
        return wanted ? allowed : cb.not(allowed);
    }
}
