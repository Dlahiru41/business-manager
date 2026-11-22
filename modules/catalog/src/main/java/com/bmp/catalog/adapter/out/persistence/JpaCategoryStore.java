package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.Category;
import com.bmp.catalog.domain.port.CategoryRepository;
import com.bmp.catalog.domain.port.PageKey;
import com.bmp.shared.context.TenantContext;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link CategoryRepository} on Spring Data JPA, with path maintenance in SQL. */
@Repository
class JpaCategoryStore implements CategoryRepository {

    private final SpringDataCategories categories;
    private final EntityManager em;
    private final JdbcClient jdbc;

    JpaCategoryStore(SpringDataCategories categories, EntityManager em, JdbcClient jdbc) {
        this.categories = categories;
        this.em = em;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Category> findById(UUID id) {
        return categories.findById(id);
    }

    @Override
    public Category save(Category category) {
        return categories.save(category);
    }

    @Override
    public Category saveAndFlush(Category category) {
        return categories.saveAndFlush(category);
    }

    @Override
    public List<Category> findPage(@Nullable UUID parentId, @Nullable PageKey after, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Category> query = cb.createQuery(Category.class);
        Root<Category> category = query.from(Category.class);
        List<Predicate> where = new ArrayList<>();
        if (parentId != null) {
            where.add(cb.equal(category.get("parentId"), parentId));
        }
        if (after != null) {
            where.add(Keysets.after(cb, category.get("name"), category.get("id"), after));
        }
        query.select(category)
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.asc(category.get("name")), cb.asc(category.get("id")));
        return em.createQuery(query).setMaxResults(limit).getResultList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int subtreeHeight(String path) {
        Integer deepest = jdbc.sql("""
                        SELECT max(cardinality(string_to_array(path, '/'))) FROM catalog.category
                         WHERE tenant_id = :tenantId AND path LIKE :prefix
                        """)
                .param("tenantId", TenantContext.require().value())
                .param("prefix", Keysets.startsWith(path + "/"))
                .query(Integer.class)
                .optional()
                .orElse(null);
        return deepest == null ? 0 : deepest - path.split("/").length;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void rebaseDescendants(String oldPath, String newPath) {
        jdbc.sql("""
                        UPDATE catalog.category
                           SET path = :newPath || substr(path, :oldLength + 1), version = version + 1
                         WHERE tenant_id = :tenantId AND path LIKE :prefix
                        """)
                .param("newPath", newPath)
                .param("oldLength", oldPath.length())
                .param("tenantId", TenantContext.require().value())
                .param("prefix", Keysets.startsWith(oldPath + "/"))
                .update();
    }
}
