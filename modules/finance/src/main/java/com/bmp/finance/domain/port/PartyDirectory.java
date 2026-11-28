package com.bmp.finance.domain.port;

import java.util.Optional;
import java.util.UUID;

/** Finance's replica of parties (payment terms), fed by party events. */
public interface PartyDirectory {

    /** A party's type and payment terms. */
    record PartyTerms(UUID partyId, String type, int paymentTermsDays, String status) {}

    /** Inserts or, when {@code version} is newer, updates the party. */
    void upsert(PartyTerms terms, long version);

    Optional<PartyTerms> find(UUID partyId);
}
