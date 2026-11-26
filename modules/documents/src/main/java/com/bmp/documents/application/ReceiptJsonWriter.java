package com.bmp.documents.application;

import com.bmp.receipt.ReceiptModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** A receipt as documents.yaml {@code ReceiptModel} carries it in a print job: amounts as strings, local time. */
final class ReceiptJsonWriter {

    private ReceiptJsonWriter() {}

    /** The receipt as JSON; its time is local to the tenant. */
    static String write(JsonMapper json, ReceiptModel m, ZoneId zone) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("billId", m.billId().toString());
        root.put("billNumber", m.billNumber());
        root.put(
                "issuedAt",
                LocalDateTime.ofInstant(m.issuedAt(), zone)
                        .truncatedTo(ChronoUnit.SECONDS)
                        .toString());
        if (m.cashier() != null) {
            root.put("cashier", m.cashier());
        }
        if (m.register() != null) {
            root.put("register", m.register());
        }
        root.put(
                "lines",
                m.lines().stream()
                        .map(l -> Map.of(
                                "name", l.name(),
                                "qty", plain(l.qty()),
                                "unit", l.unit(),
                                "unitPrice", money(l.unitPrice()),
                                "lineTotal", money(l.lineTotal()),
                                "discount", money(l.discount())))
                        .toList());
        root.put("discountTotal", money(m.discountTotal()));
        root.put("taxTotal", money(m.taxTotal()));
        root.put("total", money(m.total()));
        root.put(
                "tenders",
                m.tenders().stream()
                        .map(t -> Map.of("method", t.method(), "amount", money(t.amount())))
                        .toList());
        root.put("change", money(m.change()));
        if (m.customer() != null) {
            root.put("customer", m.customer());
        }
        root.put("currency", m.currency());
        root.put("copy", m.copy());
        root.put("cashTendered", m.cashTendered());
        return json.writeValueAsString(root);
    }

    static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
