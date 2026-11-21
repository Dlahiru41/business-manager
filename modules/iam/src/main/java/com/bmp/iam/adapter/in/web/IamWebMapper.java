package com.bmp.iam.adapter.in.web;

import com.bmp.iam.adapter.in.web.generated.model.Device;
import com.bmp.iam.adapter.in.web.generated.model.Role;
import com.bmp.iam.adapter.in.web.generated.model.RoleAssignment;
import com.bmp.iam.adapter.in.web.generated.model.User;
import com.bmp.iam.application.command.RoleRequest;
import com.bmp.iam.application.query.DeviceView;
import com.bmp.iam.application.query.RoleView;
import com.bmp.iam.application.query.UserView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Read models to API representations, and role assignments to commands. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface IamWebMapper {

    User toDto(UserView view);

    Role toDto(RoleView view);

    Device toDto(DeviceView view);

    default @Nullable OffsetDateTime toOffsetDateTime(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    default List<RoleRequest> toCommand(List<RoleAssignment> assignments) {
        return assignments.stream()
                .map(assignment -> {
                    List<UUID> scope = assignment.getLocationScope();
                    return new RoleRequest(assignment.getCode(), scope != null ? scope : List.of());
                })
                .toList();
    }
}
