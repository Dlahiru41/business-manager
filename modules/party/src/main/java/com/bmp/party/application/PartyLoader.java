package com.bmp.party.application;

import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.party.domain.model.CustomerGroup;
import com.bmp.party.domain.model.Party;
import com.bmp.party.domain.port.CustomerGroupRepository;
import com.bmp.party.domain.port.PartyRepository;
import com.bmp.shared.error.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads party aggregates of the current tenant, or answers 404 (also for other tenants' ids). */
@Component
public class PartyLoader {

    private final PartyRepository parties;
    private final CustomerGroupRepository groups;

    public PartyLoader(PartyRepository parties, CustomerGroupRepository groups) {
        this.parties = parties;
        this.groups = groups;
    }

    public Party party(UUID id) {
        return parties.findById(id)
                .orElseThrow(() -> new NotFoundException(PartyErrorCode.PARTY_NOT_FOUND, Map.of("partyId", id)));
    }

    public CustomerGroup group(UUID id) {
        return groups.findById(id)
                .orElseThrow(
                        () -> new NotFoundException(PartyErrorCode.CUSTOMER_GROUP_NOT_FOUND, Map.of("groupId", id)));
    }
}
