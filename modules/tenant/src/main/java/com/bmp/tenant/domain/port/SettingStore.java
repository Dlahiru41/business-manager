package com.bmp.tenant.domain.port;

import com.bmp.tenant.domain.model.SettingGroup;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Stored setting values of the current tenant as JSON text (only values that differ from defaults are stored), plus
 * the append-only change history.
 */
public interface SettingStore {

    /** Stored values of a group by key, as JSON. */
    Map<String, StoredValue> load(SettingGroup group);

    /** Locks the tenant's settings for the rest of the transaction, so concurrent updates serialize. */
    void lock();

    void write(SettingGroup group, String key, String json, @Nullable String previousJson, long version);

    /**
     * A stored value.
     *
     * @param json the value as JSON text
     * @param version the group version that last changed it
     */
    record StoredValue(String json, long version) {}
}
