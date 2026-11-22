package com.bmp.tenant.adapter.in.web;

import com.bmp.tenant.adapter.in.web.generated.api.SettingsApi;
import com.bmp.tenant.adapter.in.web.generated.model.SettingGroup;
import com.bmp.tenant.adapter.in.web.generated.model.SettingGroups;
import com.bmp.tenant.adapter.in.web.generated.model.UpdateSettingsRequest;
import com.bmp.tenant.application.command.UpdateSettingsHandler;
import com.bmp.tenant.application.query.SettingGroupView;
import com.bmp.tenant.application.query.TenantQueryService;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Business settings of the caller's tenant (TEN-02, TEN-03). */
@RestController
class SettingsController implements SettingsApi {

    private final TenantQueryService queries;
    private final UpdateSettingsHandler updateSettings;
    private final TenantWebMapper mapper;
    private final JsonDocuments documents;

    SettingsController(
            TenantQueryService queries,
            UpdateSettingsHandler updateSettings,
            TenantWebMapper mapper,
            JsonDocuments documents) {
        this.queries = queries;
        this.updateSettings = updateSettings;
        this.mapper = mapper;
        this.documents = documents;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:read')")
    public ResponseEntity<SettingGroups> listSettings() {
        return ResponseEntity.ok(new SettingGroups()
                .groups(queries.allSettings().stream().map(mapper::toDto).toList()));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:read')")
    public ResponseEntity<SettingGroup> getSettings(String group) {
        return respond(queries.settings(group));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'tenant:settings')")
    public ResponseEntity<SettingGroup> updateSettings(
            String group, UpdateSettingsRequest request, @Nullable String ifMatch) {
        return respond(updateSettings.handle(new UpdateSettingsHandler.UpdateSettings(
                group, IfMatch.expectedVersion(ifMatch), documents.toNodes(request.getValues()))));
    }

    private ResponseEntity<SettingGroup> respond(SettingGroupView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
