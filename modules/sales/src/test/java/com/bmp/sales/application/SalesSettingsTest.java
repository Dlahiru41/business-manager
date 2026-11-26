package com.bmp.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SalesSettingsTest {

    @Test
    void settingValuesLoseTheirJsonQuotesOnlyWhenQuotedOnBothSides() {
        assertThat(SalesSettings.unquoted(" \"5.00\" ")).isEqualTo("5.00");
        assertThat(SalesSettings.unquoted("7")).isEqualTo("7");
        assertThat(SalesSettings.unquoted("true")).isEqualTo("true");
        assertThat(SalesSettings.unquoted("\""))
                .as("a lone quote is not a quoted value")
                .isEqualTo("\"");
        assertThat(SalesSettings.unquoted("\"open")).isEqualTo("\"open");
        assertThat(SalesSettings.unquoted("open\"")).isEqualTo("open\"");
        assertThat(SalesSettings.unquoted("\"\"")).isEmpty();
    }
}
