package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.CountStatus;
import com.bmp.inventory.domain.model.StockCount;
import com.bmp.inventory.domain.port.StockCountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link StockCountRepository} on JPA; filters are added only when given. */
@Repository
class JpaStockCountStore implements StockCountRepository {

    private final SpringDataStockCounts counts;
    private final EntityManager em;

    JpaStockCountStore(SpringDataStockCounts counts, EntityManager em) {
        this.counts = counts;
        this.em = em;
    }

    @Override
    public Optional<StockCount> findById(UUID id) {
        return counts.findById(id);
    }

    @Override
    public List<StockCount> findPage(
            @Nullable CountStatus status, @Nullable UUID locationId, @Nullable UUID beforeId, int limit) {
        StringBuilder jpql = new StringBuilder("select c from StockCount c where 1 = 1");
        if (status != null) {
            jpql.append(" and c.status = :status");
        }
        if (locationId != null) {
            jpql.append(" and c.locationId = :locationId");
        }
        if (beforeId != null) {
            jpql.append(" and c.id < :beforeId");
        }
        TypedQuery<StockCount> query =
                em.createQuery(jpql.append(" order by c.id desc").toString(), StockCount.class);
        if (status != null) {
            query.setParameter("status", status);
        }
        if (locationId != null) {
            query.setParameter("locationId", locationId);
        }
        if (beforeId != null) {
            query.setParameter("beforeId", beforeId);
        }
        return query.setMaxResults(limit).getResultList();
    }

    @Override
    public StockCount saveAndFlush(StockCount count) {
        return counts.saveAndFlush(count);
    }
}
