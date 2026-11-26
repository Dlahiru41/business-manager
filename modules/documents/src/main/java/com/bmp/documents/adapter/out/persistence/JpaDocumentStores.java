package com.bmp.documents.adapter.out.persistence;

import com.bmp.documents.domain.model.PrintAgent;
import com.bmp.documents.domain.model.PrintJob;
import com.bmp.documents.domain.port.PrintAgentRepository;
import com.bmp.documents.domain.port.PrintJobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** JPA stores of print agents and jobs; JPQL is built only from fixed fragments, values are bound. */
final class JpaDocumentStores {

    private JpaDocumentStores() {}

    @Repository
    static class Agents implements PrintAgentRepository {

        private final EntityManager em;

        Agents(EntityManager em) {
            this.em = em;
        }

        @Override
        public Optional<PrintAgent> findById(UUID id) {
            return Optional.ofNullable(em.find(PrintAgent.class, id));
        }

        @Override
        public List<PrintAgent> atLocation(UUID locationId) {
            return em.createQuery("select a from PrintAgent a where a.locationId = :l order by a.id", PrintAgent.class)
                    .setParameter("l", locationId)
                    .getResultList();
        }

        @Override
        public List<PrintAgent> page(@Nullable UUID locationId, @Nullable UUID beforeId, int limit) {
            StringBuilder jpql = new StringBuilder("select a from PrintAgent a where 1 = 1");
            if (locationId != null) {
                jpql.append(" and a.locationId = :locationId");
            }
            if (beforeId != null) {
                jpql.append(" and a.id < :beforeId");
            }
            TypedQuery<PrintAgent> query =
                    em.createQuery(jpql.append(" order by a.id desc").toString(), PrintAgent.class);
            if (locationId != null) {
                query.setParameter("locationId", locationId);
            }
            if (beforeId != null) {
                query.setParameter("beforeId", beforeId);
            }
            return query.setMaxResults(limit).getResultList();
        }

        @Override
        public PrintAgent saveAndFlush(PrintAgent agent) {
            PrintAgent saved = em.contains(agent) ? agent : em.merge(agent);
            em.flush();
            return saved;
        }
    }

    @Repository
    static class Jobs implements PrintJobRepository {

        /** Hibernate's lock timeout for SKIP LOCKED. */
        private static final int SKIP_LOCKED = -2;

        private final EntityManager em;

        Jobs(EntityManager em) {
            this.em = em;
        }

        @Override
        public Optional<PrintJob> findById(UUID id) {
            return Optional.ofNullable(em.find(PrintJob.class, id));
        }

        @Override
        public Optional<PrintJob> lock(UUID id) {
            return Optional.ofNullable(em.find(PrintJob.class, id, LockModeType.PESSIMISTIC_WRITE));
        }

        @Override
        public Optional<PrintJob> nextDue(UUID agentId, Instant now) {
            return em.createQuery("""
                            select j from PrintJob j where j.agentId = :a
                               and (j.status = 'QUEUED' or (j.status = 'SENT' and j.sentAt <= :cutoff))
                             order by j.audit.createdAt, j.id""", PrintJob.class)
                    .setParameter("a", agentId)
                    .setParameter("cutoff", now.minus(PrintJob.RESULT_TIMEOUT))
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", SKIP_LOCKED)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst();
        }

        @Override
        public PrintJob saveAndFlush(PrintJob job) {
            PrintJob saved = em.contains(job) ? job : em.merge(job);
            em.flush();
            return saved;
        }
    }
}
