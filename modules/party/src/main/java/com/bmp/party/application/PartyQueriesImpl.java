package com.bmp.party.application;

import com.bmp.party.api.PartyQueries;
import com.bmp.party.api.dto.PartyInfo;
import com.bmp.party.domain.model.Party;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.port.PartyRepository;
import com.bmp.shared.id.PartyId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link PartyQueries} for other modules: parties of the current tenant without contact details. */
@Component
class PartyQueriesImpl implements PartyQueries {

    /** More parties than a shop has; a till takes all its customers. */
    private static final int ALL = 100_000;

    private final PartyRepository parties;

    PartyQueriesImpl(PartyRepository parties) {
        this.parties = parties;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PartyInfo> findParty(PartyId party) {
        return parties.findById(party.value()).map(PartyQueriesImpl::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyInfo> activeCustomers() {
        return parties.findPage(null, PartyStatus.ACTIVE, null, null, ALL).stream()
                .map(PartyQueriesImpl::info)
                .filter(PartyInfo::isCustomer)
                .toList();
    }

    private static PartyInfo info(Party party) {
        return new PartyInfo(
                PartyId.of(party.id()),
                party.type().name(),
                party.displayName(),
                party.customerGroupId(),
                party.isActive(),
                party.creditPolicy().creditAllowed(),
                party.creditPolicy().creditLimit(),
                party.creditPolicy().paymentTermsDays());
    }
}
