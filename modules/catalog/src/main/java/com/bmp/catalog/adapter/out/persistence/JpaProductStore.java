package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.port.PageKey;
import com.bmp.catalog.domain.port.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link ProductRepository} on Spring Data JPA; RLS and {@code @TenantId} scope every query to the tenant. */
@Repository
class JpaProductStore implements ProductRepository {

    private final SpringDataProducts products;
    private final EntityManager em;

    JpaProductStore(SpringDataProducts products, EntityManager em) {
        this.products = products;
        this.em = em;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return products.findById(id);
    }

    @Override
    public Product save(Product product) {
        return products.save(product);
    }

    @Override
    public Product saveAndFlush(Product product) {
        return products.saveAndFlush(product);
    }

    @Override
    public List<Product> findPage(Filter filter, @Nullable PageKey after, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Product> query = cb.createQuery(Product.class);
        Root<Product> product = query.from(Product.class);
        List<Predicate> where = new ArrayList<>();
        String q = filter.q();
        if (q != null) {
            String pattern = Keysets.contains(q);
            where.add(cb.or(
                    cb.like(cb.lower(product.get("code")), pattern, Keysets.ESCAPE),
                    cb.like(cb.lower(product.get("name")), pattern, Keysets.ESCAPE)));
        }
        if (filter.categoryId() != null) {
            where.add(cb.equal(product.get("categoryId"), filter.categoryId()));
        }
        if (filter.status() != null) {
            where.add(cb.equal(product.get("status"), filter.status()));
        }
        if (filter.template() != null) {
            where.add(cb.equal(product.get("template"), filter.template()));
        }
        if (after != null) {
            where.add(Keysets.after(cb, product.get("code"), product.get("id"), after));
        }
        query.select(product)
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.asc(product.get("code")), cb.asc(product.get("id")));
        return em.createQuery(query).setMaxResults(limit).getResultList();
    }

    @Override
    public List<Product> findChangedSince(long revision) {
        return em.createQuery(
                        "select p from Product p where p.revision > :revision order by p.revision, p.id", Product.class)
                .setParameter("revision", revision)
                .getResultList();
    }
}
