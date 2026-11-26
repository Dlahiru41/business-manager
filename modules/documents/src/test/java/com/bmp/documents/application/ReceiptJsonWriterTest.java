package com.bmp.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.receipt.ReceiptModel;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReceiptJsonWriterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ReceiptModel receipt(String cashier, String register, String customer) {
        return new ReceiptModel(
                UUID.randomUUID(),
                "R1-000042",
                Instant.parse("2026-11-25T09:00:00.123Z"),
                cashier,
                register,
                List.of(new ReceiptModel.Line(
                        "Karola dried fish",
                        new BigDecimal("0.350"),
                        "kg",
                        new BigDecimal("2400"),
                        new BigDecimal("840"),
                        BigDecimal.ZERO)),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("840"),
                List.of(new ReceiptModel.Tender("CREDIT", new BigDecimal("840"))),
                BigDecimal.ZERO,
                customer,
                "LKR",
                true,
                false);
    }

    @Test
    void doc02AReceiptInAPrintJobCarriesAmountsAsStringsAndTheLocalTime() {
        JsonNode json = JSON.readTree(
                ReceiptJsonWriter.write(JSON, receipt("Nimal", "R1", "Hotel Lake View"), ZoneId.of("Asia/Colombo")));

        assertThat(json.path("issuedAt").asString()).isEqualTo("2026-11-25T14:30");
        assertThat(json.path("cashier").asString()).isEqualTo("Nimal");
        assertThat(json.path("register").asString()).isEqualTo("R1");
        assertThat(json.path("customer").asString()).isEqualTo("Hotel Lake View");
        assertThat(json.path("lines").get(0).path("qty").asString()).isEqualTo("0.35");
        assertThat(json.path("lines").get(0).path("unitPrice").asString()).isEqualTo("2400.00");
        assertThat(json.path("total").asString()).isEqualTo("840.00");
        assertThat(json.path("copy").asBoolean()).isTrue();
    }

    @Test
    void whatABillDoesNotHaveIsLeftOut() {
        JsonNode json = JSON.readTree(ReceiptJsonWriter.write(JSON, receipt(null, null, null), ZoneId.of("UTC")));

        assertThat(json.has("cashier")).isFalse();
        assertThat(json.has("register")).isFalse();
        assertThat(json.has("customer")).isFalse();
        assertThat(ReceiptJsonWriter.plain(new BigDecimal("300.000"))).isEqualTo("300");
    }
}
