package com.bmp.tenant.application.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.NotFoundException;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.port.SettingStore;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SettingsCatalogTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SettingsCatalog catalog = new SettingsCatalog(JSON);

    @Test
    void shouldExposeTheDocumentedDefaults() {
        assertThat(catalog.defaults(SettingGroup.GENERAL).path("currency").asString())
                .isEqualTo("LKR");
        assertThat(catalog.defaults(SettingGroup.INVENTORY)
                        .path("negativeStockPolicy")
                        .asString())
                .isEqualTo("ALLOW_AND_FLAG");
        assertThat(catalog.defaults(SettingGroup.PAYMENTS)
                        .path("drawerVarianceAlertAmount")
                        .asString())
                .isEqualTo("500.00");
        assertThat(catalog.keys(SettingGroup.SALES))
                .containsExactlyInAnyOrder(
                        "maxCashierDiscountPct",
                        "returnWindowDays",
                        "voidRequiresManager",
                        "allowCreditSales",
                        "offlineMaxHours",
                        "rounding",
                        "wholesaleRequiresCustomer",
                        "tenderReferenceRequired");
    }

    @Test
    void defaultsAreCopiesSoCallersCannotChangeThem() {
        catalog.defaults(SettingGroup.SALES).put("returnWindowDays", 99);

        assertThat(catalog.defaults(SettingGroup.SALES).path("returnWindowDays").asInt())
                .isEqualTo(7);
    }

    @Test
    void everyGroupsDefaultsPassItsOwnSchema() {
        for (SettingGroup group : SettingGroup.values()) {
            assertThat(catalog.validate(group, catalog.defaults(group)))
                    .as(group.code())
                    .isEmpty();
        }
    }

    @Test
    void shouldReportOneFieldErrorPerInvalidValue() {
        ObjectNode sales = catalog.defaults(SettingGroup.SALES);
        sales.put("offlineMaxHours", 0);
        sales.put("rounding", "BANKERS");

        assertThat(catalog.validate(SettingGroup.SALES, sales))
                .extracting(e -> e.field() + ":" + e.code())
                .containsExactlyInAnyOrder("offlineMaxHours:MINIMUM", "rounding:ENUM");
    }

    @Test
    void shouldNormaliseValidatorLocations() {
        assertThat(SettingsCatalog.fieldName("/returnWindowDays")).isEqualTo("returnWindowDays");
        assertThat(SettingsCatalog.fieldName("$.returnWindowDays")).isEqualTo("returnWindowDays");
        assertThat(SettingsCatalog.fieldName("/languages/1")).isEqualTo("languages.1");
        assertThat(SettingsCatalog.fieldName("")).isEmpty();
    }

    @Test
    void readerOverlaysStoredValuesAndIgnoresRetiredKeys() {
        SettingStore store = new SettingStore() {
            @Override
            public Map<String, StoredValue> load(SettingGroup group) {
                return Map.of("returnWindowDays", new StoredValue("14", 3), "retiredKey", new StoredValue("true", 2));
            }

            @Override
            public void lock() {}

            @Override
            public void write(
                    SettingGroup group, String key, String json, @Nullable String previousJson, long version) {}
        };
        SettingsReader reader = new SettingsReader(store, catalog, JSON);

        var view = reader.read(SettingGroup.SALES);

        assertThat(view.version()).isEqualTo(3);
        assertThat(view.values().path("returnWindowDays").asInt()).isEqualTo(14);
        assertThat(view.values().has("retiredKey")).isFalse();
        assertThatThrownBy(() -> SettingsReader.group("nope")).isInstanceOf(NotFoundException.class);
    }
}
