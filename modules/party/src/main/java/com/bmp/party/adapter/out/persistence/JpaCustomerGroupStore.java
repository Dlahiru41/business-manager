package com.bmp.party.adapter.out.persistence;

import com.bmp.party.domain.model.CustomerGroup;
import com.bmp.party.domain.port.CustomerGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** {@link CustomerGroupRepository} on JPA. */
@Repository
class JpaCustomerGroupStore implements CustomerGroupRepository {

    private final SpringDataCustomerGroups groups;
    private final EntityManager em;

    JpaCustomerGroupStore(SpringDataCustomerGroups groups, EntityManager em) {
        this.groups = groups;
        this.em = em;
    }

    @Override
    public Optional<CustomerGroup> findById(UUID id) {
        return groups.findById(id);
    }

    @Override
    public List<CustomerGroup> findPage(@Nullable String afterCode, int limit) {
        TypedQuery<CustomerGroup> query = em.createQuery(
                afterCode == null
                        ? "select g from CustomerGroup g order by g.code"
                        : "select g from CustomerGroup g where g.code > :after order by g.code",
                CustomerGroup.class);
        if (afterCode != null) {
            query.setParameter("after", afterCode);
        }
        return query.setMaxResults(limit).getResultList();
    }

    @Override
    public CustomerGroup saveAndFlush(CustomerGroup group) {
        return groups.saveAndFlush(group);
    }
}
