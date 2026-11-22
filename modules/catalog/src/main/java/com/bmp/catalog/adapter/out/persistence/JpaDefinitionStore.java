package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.model.DefinitionStatus;
import com.bmp.catalog.domain.model.TransformationDefinition;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.model.TransformationLine;
import com.bmp.catalog.domain.port.PageKey;
import com.bmp.catalog.domain.port.TransformationDefinitionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link TransformationDefinitionRepository} on Spring Data JPA. */
@Repository
class JpaDefinitionStore implements TransformationDefinitionRepository {

    private final SpringDataDefinitions definitions;
    private final EntityManager em;

    JpaDefinitionStore(SpringDataDefinitions definitions, EntityManager em) {
        this.definitions = definitions;
        this.em = em;
    }

    @Override
    public Optional<TransformationDefinition> findById(UUID id) {
        return definitions.findById(id);
    }

    @Override
    public TransformationDefinition save(TransformationDefinition definition) {
        return definitions.save(definition);
    }

    @Override
    public TransformationDefinition saveAndFlush(TransformationDefinition definition) {
        return definitions.saveAndFlush(definition);
    }

    @Override
    public List<TransformationDefinition> findUsingPackagingUnits(Collection<UUID> packagingUnitIds) {
        if (packagingUnitIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select distinct d from TransformationDefinition d join d.lines l
                         where l.packagingUnitId in :unitIds
                        """, TransformationDefinition.class)
                .setParameter("unitIds", packagingUnitIds)
                .getResultList();
    }

    @Override
    public List<TransformationDefinition> findPage(
            @Nullable TransformationKind kind,
            @Nullable DefinitionStatus status,
            @Nullable UUID variantId,
            @Nullable PageKey after,
            int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<TransformationDefinition> query = cb.createQuery(TransformationDefinition.class);
        Root<TransformationDefinition> definition = query.from(TransformationDefinition.class);
        List<Predicate> where = new ArrayList<>();
        if (kind != null) {
            where.add(cb.equal(definition.get("kind"), kind));
        }
        if (status != null) {
            where.add(cb.equal(definition.get("status"), status));
        }
        if (variantId != null) {
            Subquery<UUID> lines = query.subquery(UUID.class);
            Root<TransformationDefinition> correlated = lines.correlate(definition);
            Join<TransformationDefinition, TransformationLine> line = correlated.join("lines");
            lines.select(line.get("id")).where(cb.equal(line.get("variantId"), variantId));
            where.add(cb.exists(lines));
        }
        if (after != null) {
            where.add(Keysets.after(cb, definition.get("code"), definition.get("id"), after));
        }
        query.select(definition)
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.asc(definition.get("code")), cb.asc(definition.get("id")));
        return em.createQuery(query).setMaxResults(limit).getResultList();
    }
}
