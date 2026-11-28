package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.ExpenseCategory;
import com.bmp.finance.domain.port.ExpenseCategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
class JpaExpenseCategories implements ExpenseCategoryRepository {

    private final SpringDataExpenseCategories categories;
    private final EntityManager em;

    JpaExpenseCategories(SpringDataExpenseCategories categories, EntityManager em) {
        this.categories = categories;
        this.em = em;
    }

    @Override
    public Optional<ExpenseCategory> findById(UUID id) {
        return categories.findById(id);
    }

    @Override
    public List<ExpenseCategory> page(@Nullable String afterCode, int limit) {
        String jpql = afterCode == null
                ? "select c from ExpenseCategory c order by c.code"
                : "select c from ExpenseCategory c where c.code > :after order by c.code";
        TypedQuery<ExpenseCategory> query =
                em.createQuery(jpql, ExpenseCategory.class).setMaxResults(limit);
        if (afterCode != null) {
            query.setParameter("after", afterCode);
        }
        return query.getResultList();
    }

    @Override
    public ExpenseCategory save(ExpenseCategory category) {
        return categories.saveAndFlush(category);
    }
}
