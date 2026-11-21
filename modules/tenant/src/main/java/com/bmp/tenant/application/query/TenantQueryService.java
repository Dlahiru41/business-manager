package com.bmp.tenant.application.query;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.tenant.application.settings.SettingsReader;
import com.bmp.tenant.domain.error.TenantErrorCode;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.ReceiptTemplateRepository;
import com.bmp.tenant.domain.port.TenantRepository;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the tenant module for the current tenant; returns read models, never aggregates. */
@Service
@Transactional(readOnly = true)
public class TenantQueryService {

    /** Start of the keyset for the first page: before any row can have been created. */
    private static final Instant FIRST_PAGE = Instant.parse("1970-01-01T00:00:00Z");

    private static final UUID NIL = new UUID(0L, 0L);
    private static final int CURSOR_VALUES = 2;

    private final TenantRepository tenants;
    private final LocationRepository locations;
    private final ReceiptTemplateRepository templates;
    private final SettingsReader settings;

    public TenantQueryService(
            TenantRepository tenants,
            LocationRepository locations,
            ReceiptTemplateRepository templates,
            SettingsReader settings) {
        this.tenants = tenants;
        this.locations = locations;
        this.templates = templates;
        this.settings = settings;
    }

    public TenantView currentTenant() {
        UUID id = TenantContext.require().value();
        return tenants.findById(id)
                .map(TenantView::of)
                .orElseThrow(() -> new NotFoundException(TenantErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", id)));
    }

    public PageResponse<LocationView> locations(PageQuery query) {
        Keyset after = Keyset.of(query);
        var page = locations.findPage(after.createdAt(), after.id(), query.fetchSize()).stream()
                .map(LocationView::of)
                .toList();
        return PageResponse.of(page, query, v -> List.of(v.createdAt().toString(), v.id().toString()));
    }

    public LocationView location(UUID id) {
        return locations
                .findById(id)
                .map(LocationView::of)
                .orElseThrow(() -> new NotFoundException(TenantErrorCode.LOCATION_NOT_FOUND, Map.of("locationId", id)));
    }

    public PageResponse<ReceiptTemplateView> receiptTemplates(PageQuery query) {
        Keyset after = Keyset.of(query);
        var page = templates.findPage(after.createdAt(), after.id(), query.fetchSize()).stream()
                .map(ReceiptTemplateView::of)
                .toList();
        return PageResponse.of(page, query, v -> List.of(v.createdAt().toString(), v.id().toString()));
    }

    public ReceiptTemplateView receiptTemplate(UUID id) {
        return templates
                .findById(id)
                .map(ReceiptTemplateView::of)
                .orElseThrow(() ->
                        new NotFoundException(TenantErrorCode.RECEIPT_TEMPLATE_NOT_FOUND, Map.of("templateId", id)));
    }

    public SettingGroupView settings(String group) {
        return settings.read(SettingsReader.group(group));
    }

    public List<SettingGroupView> allSettings() {
        return Arrays.stream(SettingGroup.values()).map(settings::read).toList();
    }

    /** Position after which the next page starts: (createdAt, id) of the previous page's last row. */
    private record Keyset(Instant createdAt, UUID id) {
        static Keyset of(PageQuery query) {
            String cursor = query.cursor();
            if (cursor == null) {
                return new Keyset(FIRST_PAGE, NIL);
            }
            List<String> values = CursorCodec.decode(cursor, CURSOR_VALUES);
            try {
                return new Keyset(Instant.parse(values.get(0)), UUID.fromString(values.get(1)));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw new BusinessRuleViolation(
                        PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
            }
        }
    }
}
