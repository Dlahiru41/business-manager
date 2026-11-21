package com.bmp.audit.adapter.in.web;

import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Audit records to their API representation; network data (IP, user agent) is not exposed through the API. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface AuditWebMapper {

    @Mapping(target = "previousHash", source = "prevHash")
    com.bmp.audit.adapter.in.web.generated.model.AuditRecord toDto(AuditRecord record);

    default @Nullable OffsetDateTime toOffsetDateTime(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    default @Nullable UUID toUuid(@Nullable UserId user) {
        return user != null ? user.value() : null;
    }

    default @Nullable UUID toUuid(@Nullable DeviceId device) {
        return device != null ? device.value() : null;
    }
}
