package com.bmp.catalog.domain.service;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Rule CAT-06: SKUs are generated as {@code <PRODUCTCODE>-<VALUE1>-<VALUE2>} in upper case ({@code EGG-RED-XS}) and
 * de-duplicated with a numeric suffix ({@code EGG-RED-XS-2}). They may be overridden; uniqueness per tenant is
 * enforced by the store ({@code SKU_ALREADY_EXISTS}).
 */
public final class SkuGenerator {

    public static final int MAX_LENGTH = 64;

    private static final Pattern NOT_SKU_CHAR = Pattern.compile("[^A-Z0-9_.\\-]+");

    private SkuGenerator() {}

    /** The SKU before de-duplication. */
    public static String base(String productCode, List<String> valueCodes) {
        StringBuilder sku = new StringBuilder(clean(productCode));
        for (String valueCode : valueCodes) {
            sku.append('-').append(clean(valueCode));
        }
        return truncate(sku.toString(), MAX_LENGTH);
    }

    /**
     * The first free SKU: the base, else the base with {@code -2}, {@code -3}, ...
     *
     * @param taken whether a SKU is already used in the tenant (or by a variant generated in the same batch)
     */
    public static String generate(String productCode, List<String> valueCodes, Predicate<String> taken) {
        String base = base(productCode, valueCodes);
        if (!taken.test(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String tail = "-" + suffix;
            String candidate = truncate(base, MAX_LENGTH - tail.length()) + tail;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    private static String clean(String code) {
        return NOT_SKU_CHAR.matcher(code.toUpperCase(Locale.ROOT)).replaceAll("_");
    }

    private static String truncate(String value, int length) {
        return value.length() > length ? value.substring(0, length) : value;
    }
}
