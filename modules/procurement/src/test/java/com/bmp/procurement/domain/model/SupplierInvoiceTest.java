package com.bmp.procurement.domain.model;

import static com.bmp.procurement.domain.model.GoodsReceiptTest.assertViolation;
import static com.bmp.procurement.domain.model.ProcurementFixtures.AT;
import static com.bmp.procurement.domain.model.ProcurementFixtures.SUPPLIER;
import static com.bmp.procurement.domain.model.ProcurementFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.procurement.domain.error.ProcurementErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupplierInvoiceTest {

    static SupplierInvoice invoice(String subtotal, String tax, List<InvoiceMatch> receipts) {
        return SupplierInvoice.draft(
                UUID.randomUUID(),
                TENANT,
                SUPPLIER,
                "  INV-778 ",
                LocalDate.parse("2026-11-23"),
                LocalDate.parse("2026-12-23"),
                new BigDecimal(subtotal),
                new BigDecimal(tax),
                "LKR",
                receipts);
    }

    @Test
    void pro05ThePriceVarianceIsTheInvoiceLessTheReceiptsItMatches() {
        SupplierInvoice invoice = invoice(
                "36500.00",
                "0.00",
                List.of(
                        new InvoiceMatch(UUID.randomUUID(), "GRN-000001", new BigDecimal("36090.00")),
                        new InvoiceMatch(UUID.randomUUID(), "GRN-000002", new BigDecimal("12750.00"))));

        assertThat(invoice.receiptValue()).isEqualByComparingTo("48840.00");
        assertThat(invoice.priceVariance()).isEqualByComparingTo("-12340.00");
        assertThat(invoice.supplierRef()).isEqualTo("INV-778");
        assertThat(invoice.total()).isEqualByComparingTo("36500.00");
        assertThat(invoice(
                                "36090.00",
                                "5000.00",
                                List.of(new InvoiceMatch(UUID.randomUUID(), "GRN-1", new BigDecimal("36090.00"))))
                        .priceVariance())
                .as("tax is not price")
                .isZero();
    }

    @Test
    void pro05AnInvoiceMatchesAtLeastOneReceiptAndIsPostedOnce() {
        assertThatThrownBy(() -> invoice("1.00", "0", List.of())).isInstanceOf(IllegalArgumentException.class);
        SupplierInvoice invoice =
                invoice("100.00", "0", List.of(new InvoiceMatch(UUID.randomUUID(), "GRN-1", new BigDecimal("100.00"))));

        assertThat(invoice.status()).isEqualTo(InvoiceStatus.DRAFT);
        invoice.post(AT);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.POSTED);
        assertThat(invoice.postedAt()).isEqualTo(AT);
        assertViolation(() -> invoice.post(AT), ProcurementErrorCode.INVOICE_STATE_INVALID);
    }
}
