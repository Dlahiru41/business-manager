package com.bmp.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InventorySettingsTest {

    @Test
    void settingValuesAreJsonTextWithOrWithoutQuotes() {
        assertThat(InventorySettings.unquoted("\"FIFO\"")).isEqualTo("FIFO");
        assertThat(InventorySettings.unquoted(" \"BLOCK\" ")).isEqualTo("BLOCK");
        assertThat(InventorySettings.unquoted("FIFO")).isEqualTo("FIFO");
        assertThat(InventorySettings.unquoted("\"")).isEqualTo("\"");
        assertThat(InventorySettings.unquoted("\"FIFO")).isEqualTo("\"FIFO");
    }
}
