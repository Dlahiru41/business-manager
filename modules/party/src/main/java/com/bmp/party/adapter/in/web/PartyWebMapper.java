package com.bmp.party.adapter.in.web;

import com.bmp.party.adapter.in.web.generated.model.CustomerGroup;
import com.bmp.party.adapter.in.web.generated.model.Party;
import com.bmp.party.adapter.in.web.generated.model.PartySummary;
import com.bmp.party.application.query.CustomerGroupView;
import com.bmp.party.application.query.PartySummaryView;
import com.bmp.party.application.query.PartyView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Party read models to API representations; amounts become decimal strings. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface PartyWebMapper {

    Party toDto(PartyView view);

    PartySummary toDto(PartySummaryView view);

    CustomerGroup toDto(CustomerGroupView view);

    default @Nullable OffsetDateTime toOffsetDateTime(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** Decimal strings, never numbers (02 par. 5). */
    default @Nullable String decimal(@Nullable BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
