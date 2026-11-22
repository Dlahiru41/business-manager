package com.bmp.catalog.adapter.out.persistence;

import com.bmp.catalog.domain.port.PageKey;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.Locale;
import java.util.UUID;

/** Criteria helpers shared by the catalog stores: keyset conditions and safe {@code LIKE} patterns. */
final class Keysets {

    static final char ESCAPE = '\\';

    private Keysets() {}

    /** {@code (key, id) > (after.sortKey, after.id)} for a page ordered by key then id. */
    static Predicate after(CriteriaBuilder cb, Path<String> key, Path<UUID> id, PageKey after) {
        return cb.or(
                cb.greaterThan(key, after.sortKey()),
                cb.and(cb.equal(key, after.sortKey()), cb.greaterThan(id, after.id())));
    }

    /** A case-insensitive "contains" pattern with LIKE wildcards in the text escaped. */
    static String contains(String text) {
        String escaped = text.toLowerCase(Locale.ROOT)
                .replace(String.valueOf(ESCAPE), String.valueOf(ESCAPE) + ESCAPE)
                .replace("%", ESCAPE + "%")
                .replace("_", ESCAPE + "_");
        return "%" + escaped + "%";
    }

    /** A "starts with" pattern with LIKE wildcards in the prefix escaped. */
    static String startsWith(String prefix) {
        String escaped = prefix.replace(String.valueOf(ESCAPE), String.valueOf(ESCAPE) + ESCAPE)
                .replace("%", ESCAPE + "%")
                .replace("_", ESCAPE + "_");
        return escaped + "%";
    }
}
