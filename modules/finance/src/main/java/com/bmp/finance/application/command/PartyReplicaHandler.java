package com.bmp.finance.application.command;

import com.bmp.finance.domain.port.PartyDirectory;
import com.bmp.finance.domain.port.PartyDirectory.PartyTerms;
import com.bmp.party.api.events.CreditPolicySnapshot;
import com.bmp.shared.id.PartyId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps finance's replica of parties (type, payment terms) current; later versions win. */
@Service
public class PartyReplicaHandler {

    private final PartyDirectory parties;

    public PartyReplicaHandler(PartyDirectory parties) {
        this.parties = parties;
    }

    @Transactional
    public void replicate(PartyId party, String type, String status, CreditPolicySnapshot policy, long version) {
        parties.upsert(new PartyTerms(party.value(), type, policy.paymentTermsDays(), status), version);
    }
}
