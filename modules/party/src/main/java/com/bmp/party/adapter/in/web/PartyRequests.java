package com.bmp.party.adapter.in.web;

import com.bmp.party.adapter.in.web.generated.model.Address;
import com.bmp.party.adapter.in.web.generated.model.PartyType;
import com.bmp.party.adapter.in.web.generated.model.Status;
import com.bmp.party.adapter.in.web.generated.model.UpdateCreditPolicyRequest;
import com.bmp.party.domain.model.CreditPolicy;
import com.bmp.party.domain.model.PartyStatus;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** Request DTOs to domain values. */
final class PartyRequests {

    private PartyRequests() {}

    static com.bmp.party.domain.model.@Nullable PartyType type(@Nullable PartyType type) {
        return type == null ? null : com.bmp.party.domain.model.PartyType.valueOf(type.name());
    }

    static @Nullable PartyStatus status(@Nullable Status status) {
        return status == null ? null : PartyStatus.valueOf(status.name());
    }

    static com.bmp.party.domain.model.@Nullable Address address(@Nullable Address address) {
        if (address == null) {
            return null;
        }
        return new com.bmp.party.domain.model.Address(
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getDistrict(),
                address.getPostalCode(),
                address.getCountry());
    }

    static CreditPolicy creditPolicy(UpdateCreditPolicyRequest request) {
        return new CreditPolicy(
                request.getCreditAllowed(), new BigDecimal(request.getCreditLimit()), request.getPaymentTermsDays());
    }
}
