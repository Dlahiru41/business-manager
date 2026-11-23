package com.bmp.party.domain.port;

import com.bmp.party.domain.model.Party;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.model.PartyType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Parties of the current tenant. */
public interface PartyRepository {

    Optional<Party> findById(UUID id);

    /** Parties with this E.164 phone (PTY-02). */
    List<Party> findByPhone(String phone);

    /** A page of parties, newest first (ids are UUIDv7), after {@code beforeId}. */
    List<Party> findPage(
            @Nullable PartyType type,
            @Nullable PartyStatus status,
            @Nullable UUID customerGroupId,
            @Nullable UUID beforeId,
            int limit);

    /**
     * Active parties of these types whose name has a word starting with {@code wordPrefix} (lower case), or whose
     * phone starts with {@code phonePrefix}.
     */
    List<Party> search(String wordPrefix, @Nullable String phonePrefix, Collection<PartyType> types, int limit);

    Party saveAndFlush(Party party);
}
