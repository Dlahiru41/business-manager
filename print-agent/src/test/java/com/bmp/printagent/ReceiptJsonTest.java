package com.bmp.printagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.printagent.receipt.ReceiptJson;
import com.bmp.receipt.ReceiptModel;
import com.bmp.receipt.ReceiptTemplate;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReceiptJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String RECEIPT = """
            {"billId": "0193f100-0000-7000-8000-000000000001", "billNumber": "R1-000042",
             "issuedAt": "2026-11-25T14:30:00", "cashier": "Nimal", "register": "R1",
             "lines": [{"name": "Eggs Red L", "qty": "10", "unit": "piece", "unitPrice": "55.00",
                        "lineTotal": "550.00"},
                       {"name": "Coconut oil 750 ml", "qty": "1", "unitPrice": "980.00", "lineTotal": "930.00",
                        "discount": "50.00"}],
             "discountTotal": "50.00", "taxTotal": "0.00", "total": "1480.00",
             "tenders": [{"method": "CASH", "amount": "1480.00"}], "change": "20.00",
             "currency": "LKR", "cashTendered": true}""";

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void aReceiptIsReadWithItsLocalTimeAmountsAndTenders() {
        ReceiptModel receipt = ReceiptJson.receipt(json(RECEIPT));

        assertThat(receipt.billNumber()).isEqualTo("R1-000042");
        assertThat(receipt.issuedAt())
                .as("the till's local time, carried as UTC and printed in UTC")
                .isEqualTo(Instant.parse("2026-11-25T14:30:00Z"));
        assertThat(receipt.lines()).hasSize(2);
        assertThat(receipt.lines().get(0).discount()).isZero();
        assertThat(receipt.lines().get(1).unit()).isEmpty();
        assertThat(receipt.lines().get(1).discount()).isEqualByComparingTo("50.00");
        assertThat(receipt.tenders())
                .singleElement()
                .satisfies(t -> assertThat(t.method()).isEqualTo("CASH"));
        assertThat(receipt.change()).isEqualByComparingTo("20.00");
        assertThat(receipt.cashTendered()).isTrue();
        assertThat(receipt.copy()).isFalse();
        assertThat(receipt.customer()).isNull();
    }

    @Test
    void aReceiptThatCannotBeReadSaysWhy() {
        assertThatThrownBy(() -> ReceiptJson.receipt(json("[]"))).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> ReceiptJson.receipt(json(RECEIPT.replace("\"billNumber\": \"R1-000042\",", ""))))
                .hasMessageContaining("billNumber is required");
        assertThatThrownBy(() -> ReceiptJson.receipt(json(RECEIPT.replace("2026-11-25T14:30:00", "yesterday"))))
                .hasMessageContaining("issuedAt");
        assertThatThrownBy(() ->
                        ReceiptJson.receipt(json(RECEIPT.replace("\"total\": \"1480.00\"", "\"total\": \"a lot\""))))
                .hasMessageContaining("total is a decimal");
        assertThatThrownBy(
                        () -> ReceiptJson.receipt(json(RECEIPT.replace("0193f100-0000-7000-8000-000000000001", "x"))))
                .hasMessageContaining("billId is a UUID");
        assertThatThrownBy(() -> ReceiptJson.receipt(json("{\"lines\": {}}"))).hasMessageContaining("lines is a list");
        assertThatThrownBy(() -> ReceiptJson.receipt(json(RECEIPT.replace(
                        "\"tenders\": [{\"method\": \"CASH\", \"amount\": \"1480.00\"}]", "\"tenders\": \"cash\""))))
                .hasMessageContaining("tenders is a list");
    }

    @Test
    void aTemplateIsReadAsTheCloudSendsIt() {
        ReceiptTemplate template = ReceiptJson.template(json("""
                {"version": 3, "shopName": "Lanka Mart", "addressLines": ["12 Main Street"], "phone": "081 222",
                 "footerLines": ["Thank you"], "paperWidthMm": 58, "showQr": true, "openDrawerOnCash": false,
                 "language": "si"}"""));

        assertThat(template.version()).isEqualTo(3);
        assertThat(template.addressLines()).containsExactly("12 Main Street");
        assertThat(template.taxNumber()).isNull();
        assertThat(template.paperWidthMm()).isEqualTo(58);
        assertThat(template.showQr()).isTrue();
        assertThat(template.openDrawerOnCash()).isFalse();
        assertThat(template.language()).isEqualTo("si");
        assertThat(ReceiptJson.template(json("{\"version\": 0, \"shopName\": \"Shop\", \"paperWidthMm\": 80}"))
                        .language())
                .isEqualTo("en");
        assertThatThrownBy(() -> ReceiptJson.template(json("{\"shopName\": \"Shop\"}")))
                .hasMessageContaining("version");
        assertThatThrownBy(() ->
                        ReceiptJson.template(json("{\"version\": 1, \"shopName\": \"Shop\", \"paperWidthMm\": 76}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
