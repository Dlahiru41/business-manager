package com.bmp.catalog.domain.model;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Stock states of variants: {@code DEFAULT}, {@code SEALED}/{@code OPEN} for bulk goods, own-packet states. */
public final class StockStates {

    public static final String DEFAULT = "DEFAULT";
    public static final String SEALED = "SEALED";
    public static final String OPEN = "OPEN";
    /** Prefix of the state of a tenant's own packets of loose goods, e.g. {@code PACKED_PACKET_250}. */
    public static final String PACKED_PREFIX = "PACKED_";

    public static final int MAX_STATES = 20;

    private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern NOT_STATE_CHAR = Pattern.compile("[^A-Z0-9]+");

    private StockStates() {}

    /** The own-packet state of a packaging unit code: {@code packet-250} → {@code PACKED_PACKET_250}. */
    public static String packedStateFor(String unitCode) {
        String suffix = NOT_STATE_CHAR
                .matcher(unitCode.toUpperCase(Locale.ROOT))
                .replaceAll("_")
                .replaceAll("^_+|_+$", "");
        String state = PACKED_PREFIX + suffix;
        return state.length() > 32 ? state.substring(0, 32) : state;
    }

    /** Validates and de-duplicates a list of states, keeping their order. */
    public static List<String> validated(List<String> states) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(states);
        if (unique.isEmpty() || unique.size() > MAX_STATES) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.INVALID_STOCK_STATE,
                    "A variant has between 1 and " + MAX_STATES + " stock states",
                    Map.of("count", unique.size()));
        }
        for (String state : unique) {
            if (!STATE.matcher(state).matches()) {
                throw new BusinessRuleViolation(CatalogErrorCode.INVALID_STOCK_STATE, Map.of("stockState", state));
            }
        }
        return new ArrayList<>(unique);
    }

    /** Rule CAT-07: a packaging unit draws from one of its variant's states. */
    public static void requireIn(List<String> states, String state, String unitCode) {
        if (!states.contains(state)) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.INVALID_STOCK_STATE,
                    "Stock state " + state + " of unit " + unitCode + " is not one of the variant's states",
                    Map.of("stockState", state, "unitCode", unitCode, "stockStates", List.copyOf(states)));
        }
    }
}
