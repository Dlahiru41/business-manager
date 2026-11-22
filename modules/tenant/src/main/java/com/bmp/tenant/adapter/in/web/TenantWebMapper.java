package com.bmp.tenant.adapter.in.web;

import com.bmp.tenant.adapter.in.web.generated.model.Location;
import com.bmp.tenant.adapter.in.web.generated.model.ReceiptTemplate;
import com.bmp.tenant.adapter.in.web.generated.model.SettingGroup;
import com.bmp.tenant.adapter.in.web.generated.model.Tenant;
import com.bmp.tenant.application.query.LocationView;
import com.bmp.tenant.application.query.ReceiptTemplateView;
import com.bmp.tenant.application.query.SettingGroupView;
import com.bmp.tenant.application.query.TenantView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Read models to API representations. */
@Mapper(componentModel = "spring", uses = JsonDocuments.class, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface TenantWebMapper {

    Tenant toDto(TenantView view);

    Location toDto(LocationView view);

    @Mapping(target = "paperWidthMm", source = "paperWidthMm")
    ReceiptTemplate toDto(ReceiptTemplateView view);

    SettingGroup toDto(SettingGroupView view);

    default OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    default ReceiptTemplate.PaperWidthMmEnum toPaperWidth(int millimetres) {
        return ReceiptTemplate.PaperWidthMmEnum.fromValue(millimetres);
    }

    default ReceiptTemplate.LanguageEnum toLanguage(String code) {
        return ReceiptTemplate.LanguageEnum.fromValue(code);
    }
}
