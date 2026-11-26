package com.bmp.payments.adapter.out.persistence;

import com.bmp.payments.domain.model.DrawerSession;
import com.bmp.payments.domain.model.DrawerStatus;
import com.bmp.payments.domain.port.DrawerSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Drawer sessions through JPA; JPQL is built only from fixed fragments, values are bound. */
@Repository
class JpaDrawerSessions implements DrawerSessionRepository {

    private final SpringDataDrawerSessions sessions;
    private final EntityManager em;

    JpaDrawerSessions(SpringDataDrawerSessions sessions, EntityManager em) {
        this.sessions = sessions;
        this.em = em;
    }

    @Override
    public Optional<DrawerSession> findById(UUID id) {
        return sessions.findById(id);
    }

    @Override
    public Optional<DrawerSession> lock(UUID id) {
        return Optional.ofNullable(em.find(DrawerSession.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    @Override
    public Optional<DrawerSession> findActive(UUID registerId) {
        return em.createQuery(
                        "select s from DrawerSession s where s.registerId = :r and s.status in :active",
                        DrawerSession.class)
                .setParameter("r", registerId)
                .setParameter("active", List.of(DrawerStatus.OPEN, DrawerStatus.COUNTED))
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<DrawerSession> page(
            @Nullable UUID registerId, @Nullable DrawerStatus status, @Nullable UUID beforeId, int limit) {
        StringBuilder jpql = new StringBuilder("select s from DrawerSession s where 1 = 1");
        if (registerId != null) {
            jpql.append(" and s.registerId = :registerId");
        }
        if (status != null) {
            jpql.append(" and s.status = :status");
        }
        if (beforeId != null) {
            jpql.append(" and s.id < :beforeId");
        }
        TypedQuery<DrawerSession> query =
                em.createQuery(jpql.append(" order by s.id desc").toString(), DrawerSession.class);
        if (registerId != null) {
            query.setParameter("registerId", registerId);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        if (beforeId != null) {
            query.setParameter("beforeId", beforeId);
        }
        return query.setMaxResults(limit).getResultList();
    }

    @Override
    public DrawerSession saveAndFlush(DrawerSession session) {
        return sessions.saveAndFlush(session);
    }
}
