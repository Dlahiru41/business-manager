package com.bmp.catalog.domain.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Codes of definitions created by templates: {@code <PRODUCT>-<KIND>-<UNIT>}, e.g. {@code OIL-OPEN-CAN}. */
final class DefinitionCodes {

    static final int MAX_LENGTH = 40;

    private static final Pattern NOT_CODE_CHAR = Pattern.compile("[^A-Z0-9_-]+");

    private DefinitionCodes() {}

    static String of(String productCode, String kind, String unitCode) {
        String code = NOT_CODE_CHAR
                .matcher((productCode + "-" + kind + "-" + unitCode).toUpperCase(Locale.ROOT))
                .replaceAll("-");
        return code.length() > MAX_LENGTH ? code.substring(0, MAX_LENGTH) : code;
    }
}
