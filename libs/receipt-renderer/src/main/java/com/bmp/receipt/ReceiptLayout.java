package com.bmp.receipt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The rows of a receipt at the paper's width (DOC-02): header, bill, lines, totals, tenders, footer. Every format
 * renders these rows, so a receipt reads the same on paper, in a PDF and in a preview.
 */
final class ReceiptLayout {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private ReceiptLayout() {}

    enum Style {
        NORMAL,
        BOLD,
        LARGE
    }

    enum Align {
        LEFT,
        CENTER
    }

    /** One printed row; {@code qr} rows hold the text to encode instead of text to print. */
    record Row(String text, Style style, Align align, boolean qr) {

        static Row left(String text) {
            return new Row(text, Style.NORMAL, Align.LEFT, false);
        }

        static Row center(String text, Style style) {
            return new Row(text, style, Align.CENTER, false);
        }

        /** Whether the printer's text mode can print it: plain ASCII. Anything else is drawn as an image (DOC-03). */
        boolean plain() {
            return text.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
        }
    }

    static List<Row> rows(ReceiptModel m, ReceiptTemplate t) {
        int width = t.columns();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.center(t.shopName(), Style.LARGE));
        t.addressLines().forEach(a -> rows.add(Row.center(a, Style.NORMAL)));
        if (t.phone() != null) {
            rows.add(Row.center("Tel: " + t.phone(), Style.NORMAL));
        }
        if (t.taxNumber() != null) {
            rows.add(Row.center("Tax no: " + t.taxNumber(), Style.NORMAL));
        }
        if (m.copy()) {
            rows.add(Row.center("*** COPY ***", Style.BOLD));
        }
        rows.add(Row.left("-".repeat(width)));
        rows.add(
                Row.left(pair("Bill " + m.billNumber(), WHEN.format(m.issuedAt().atZone(t.timeZone())), width)));
        if (m.cashier() != null || m.register() != null) {
            rows.add(Row.left(pair(
                    m.cashier() == null ? "" : "Cashier " + m.cashier(),
                    m.register() == null ? "" : "Register " + m.register(),
                    width)));
        }
        rows.add(Row.left("-".repeat(width)));
        for (ReceiptModel.Line line : m.lines()) {
            wrap(line.name(), width).forEach(part -> rows.add(Row.left(part)));
            rows.add(Row.left(pair(
                    "  " + qty(line.qty()) + " " + line.unit() + " x " + money(line.unitPrice()),
                    money(line.lineTotal()),
                    width)));
            if (line.discount().signum() > 0) {
                rows.add(Row.left(pair("  Discount", "-" + money(line.discount()), width)));
            }
        }
        rows.add(Row.left("-".repeat(width)));
        if (m.discountTotal().signum() > 0) {
            rows.add(Row.left(pair("Discount", "-" + money(m.discountTotal()), width)));
        }
        if (m.taxTotal().signum() > 0) {
            rows.add(Row.left(pair("Tax included", money(m.taxTotal()), width)));
        }
        rows.add(new Row(pair("TOTAL " + m.currency(), money(m.total()), width), Style.BOLD, Align.LEFT, false));
        m.tenders().forEach(tender -> rows.add(Row.left(pair(label(tender.method()), money(tender.amount()), width))));
        if (m.change().signum() > 0) {
            rows.add(Row.left(pair("Change", money(m.change()), width)));
        }
        if (m.customer() != null) {
            rows.add(Row.left("Customer: " + m.customer()));
        }
        if (!t.footerLines().isEmpty()) {
            rows.add(Row.left(""));
            t.footerLines().forEach(f -> rows.add(Row.center(f, Style.NORMAL)));
        }
        if (t.showQr()) {
            rows.add(new Row(m.billId().toString(), Style.NORMAL, Align.CENTER, true));
        }
        return rows;
    }

    /** {@code left} and {@code right} on one line of {@code width}, or {@code right} below when they do not fit. */
    static String pair(String left, String right, int width) {
        int gap = width - left.length() - right.length();
        if (gap >= 1) {
            return left + " ".repeat(gap) + right;
        }
        return left + "\n" + " ".repeat(Math.max(0, width - right.length())) + right;
    }

    static List<String> wrap(String text, int width) {
        List<String> parts = new ArrayList<>();
        String rest = text.strip();
        while (rest.length() > width) {
            int cut = rest.lastIndexOf(' ', width);
            if (cut <= 0) {
                cut = width;
            }
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        parts.add(rest);
        return parts;
    }

    static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }

    static String qty(BigDecimal qty) {
        BigDecimal stripped = qty.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    private static String label(@Nullable String method) {
        if (method == null || method.isEmpty()) {
            return "";
        }
        String lower = method.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
