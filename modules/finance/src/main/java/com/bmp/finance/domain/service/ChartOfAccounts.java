package com.bmp.finance.domain.service;

import com.bmp.finance.domain.model.SystemKey;
import java.util.Arrays;
import java.util.List;

/** Rule FIN-01: the default chart (posting-rules.md §1) and an expense category per operating expense account. */
public final class ChartOfAccounts {

    private ChartOfAccounts() {}

    /** An expense category seeded with the chart. */
    public record DefaultCategory(String code, String name, SystemKey account) {}

    public static List<SystemKey> accounts() {
        return List.of(SystemKey.values());
    }

    public static List<DefaultCategory> expenseCategories() {
        return Arrays.stream(SystemKey.values())
                .filter(SystemKey::isOperatingExpense)
                .map(key -> new DefaultCategory(key.name().substring("EXP_".length()), key.defaultName(), key))
                .toList();
    }
}
