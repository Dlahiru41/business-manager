package com.bmp.pricing.adapter.out.persistence;

import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.port.PriceListRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** {@link PriceListRepository} on Spring Data JPA; RLS and {@code @TenantId} scope every query to the tenant. */
@Repository
class JpaPriceListStore implements PriceListRepository {

    private final SpringDataPriceLists lists;
    private final EntityManager em;

    JpaPriceListStore(SpringDataPriceLists lists, EntityManager em) {
        this.lists = lists;
        this.em = em;
    }

    @Override
    public Optional<PriceList> findById(UUID id) {
        return lists.findById(id);
    }

    @Override
    public Optional<PriceList> lockById(UUID id) {
        return Optional.ofNullable(em.find(PriceList.class, id, LockModeType.PESSIMISTIC_WRITE));
    }

    @Override
    public Optional<PriceList> findByCode(String code) {
        return em.createQuery("select l from PriceList l where l.code = :code", PriceList.class)
                .setParameter("code", code.toUpperCase(Locale.ROOT))
                .getResultStream()
                .findFirst();
    }

    @Override
    public List<PriceList> findAll() {
        return em.createQuery("select l from PriceList l order by l.priority, l.code", PriceList.class)
                .getResultList();
    }

    @Override
    public PriceList save(PriceList list) {
        return lists.save(list);
    }

    @Override
    public PriceList saveAndFlush(PriceList list) {
        return lists.saveAndFlush(list);
    }
}
