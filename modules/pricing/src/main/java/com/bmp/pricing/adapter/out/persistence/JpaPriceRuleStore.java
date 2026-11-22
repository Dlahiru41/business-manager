package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.port.PriceRuleRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** {@link PriceRuleRepository} on Spring Data JPA. */
@Repository
class JpaPriceRuleStore implements PriceRuleRepository {

    private final SpringDataPriceRules rules;
    private final EntityManager em;

    JpaPriceRuleStore(SpringDataPriceRules rules, EntityManager em) {
        this.rules = rules;
        this.em = em;
    }

    @Override
    public Optional<PriceRule> findById(UUID id) {
        return rules.findById(id);
    }

    @Override
    public List<PriceRule> findAll() {
        return em.createQuery("select r from PriceRule r order by r.priority, r.code", PriceRule.class)
                .getResultList();
    }

    @Override
    public PriceRule save(PriceRule rule) {
        return rules.save(rule);
    }

    @Override
    public PriceRule saveAndFlush(PriceRule rule) {
        return rules.saveAndFlush(rule);
    }
}
