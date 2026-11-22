package com.bmp.tenant.application.settings;

import com.bmp.shared.error.NotFoundException;
import com.bmp.tenant.application.query.SettingGroupView;
import com.bmp.tenant.domain.error.TenantErrorCode;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.port.SettingStore;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds the effective values of a group: schema defaults overlaid with the tenant's stored values. */
@Component
public class SettingsReader {

    private final SettingStore store;
    private final SettingsCatalog catalog;
    private final JsonMapper json;

    public SettingsReader(SettingStore store, SettingsCatalog catalog, JsonMapper json) {
        this.store = store;
        this.catalog = catalog;
        this.json = json;
    }

    public static SettingGroup group(String code) {
        return SettingGroup.fromCode(code)
                .orElseThrow(
                        () -> new NotFoundException(TenantErrorCode.SETTING_GROUP_NOT_FOUND, Map.of("group", code)));
    }

    public SettingGroupView read(SettingGroup group) {
        return view(group, store.load(group));
    }

    SettingGroupView view(SettingGroup group, Map<String, SettingStore.StoredValue> stored) {
        ObjectNode values = catalog.defaults(group);
        long version = 0;
        for (var entry : stored.entrySet()) {
            if (values.has(entry.getKey())) {
                values.set(entry.getKey(), json.readTree(entry.getValue().json()));
            }
            version = Math.max(version, entry.getValue().version());
        }
        return new SettingGroupView(group.code(), version, values);
    }
}
