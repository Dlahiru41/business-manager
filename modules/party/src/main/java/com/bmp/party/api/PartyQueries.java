package com.bmp.party.api;

import com.bmp.party.api.dto.PartyInfo;
import com.bmp.shared.id.PartyId;
import java.util.List;
import java.util.Optional;

/** Read-only questions other modules may ask the party module, in the current tenant. */
public interface PartyQueries {

    /** A party of the current tenant, without its contact details. */
    Optional<PartyInfo> findParty(PartyId party);

    /** Active customers (and parties that are both), newest first; for tills (SYN-02). */
    List<PartyInfo> activeCustomers();
}
