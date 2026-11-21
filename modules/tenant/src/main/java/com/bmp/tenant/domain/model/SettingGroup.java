package com.bmp.tenant.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Groups of business settings (docs/reference/domain-model.md §4, plus document numbering per ADR-012). */
public enum SettingGroup {
    GENERAL,
    INVENTORY,
    SALES,
    PRICING,
    TAX,
    FINANCE,
    PAYMENTS,
    NUMBERING;

    /** The group name used in URLs, storage and events, e.g. {@code general}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SettingGroup> fromCode(String code) {
        return Arrays.stream(values())
                .filter(group -> group.code().equals(code))
                .findFirst();
    }
}
