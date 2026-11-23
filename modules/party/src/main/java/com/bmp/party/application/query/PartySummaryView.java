package com.bmp.party.application.query;

import com.bmp.party.domain.model.Party;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.model.PartyType;
import com.bmp.shared.security.Sensitive;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A party as a search result. */
@Sensitive
public record PartySummaryView(
        UUID id,
        PartyType type,
        String displayName,
        @Nullable String phone,
        @Nullable UUID customerGroupId,
        PartyStatus status) {

    public static PartySummaryView of(Party party) {
        return new PartySummaryView(
                party.id(), party.type(), party.displayName(), party.phone(), party.customerGroupId(), party.status());
    }

    @Override
    public String toString() {
        return "PartySummaryView[id=" + id + "]";
    }
}
