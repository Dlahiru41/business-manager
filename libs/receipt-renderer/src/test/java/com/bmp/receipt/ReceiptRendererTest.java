package com.bmp.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Golden files of the pilot shop's receipts (DOC-01..DOC-04). A differing rendering is written to
 * {@code build/golden-actual} for review; copy it over the golden file when the change is intended.
 */
class ReceiptRendererTest {

    private static final UUID BILL = UUID.fromString("0193d200-0000-7000-8000-000000000001");
    private static final ReceiptRenderer RENDERER = new ReceiptRenderer(GlyphRaster.systemFonts());

    private static ReceiptTemplate template(int width, boolean qr, String shop) {
        return new ReceiptTemplate(
                3,
                shop,
                List.of("12 Main Street", "Kandy"),
                "081 222 3344",
                "VAT-123456",
                List.of("Thank you, come again"),
                width,
                qr,
                true,
                "en",
                ZoneId.of("Asia/Colombo"));
    }

    private static ReceiptModel receipt() {
        return new ReceiptModel(
                BILL,
                "R1-000042",
                Instant.parse("2026-11-25T09:00:00Z"),
                "Nimal",
                "R1",
                List.of(
                        new ReceiptModel.Line(
                                "Eggs large red",
                                new BigDecimal("10"),
                                "piece",
                                new BigDecimal("55.00"),
                                new BigDecimal("545.00"),
                                new BigDecimal("5.00")),
                        new ReceiptModel.Line(
                                "Cooking oil bottle 750 ml",
                                BigDecimal.ONE,
                                "bottle",
                                new BigDecimal("550.00"),
                                new BigDecimal("550.00"),
                                BigDecimal.ZERO),
                        new ReceiptModel.Line(
                                "Karola loose",
                                new BigDecimal("0.350"),
                                "kg",
                                new BigDecimal("2400.00"),
                                new BigDecimal("840.00"),
                                BigDecimal.ZERO)),
                new BigDecimal("5.00"),
                new BigDecimal("83.90"),
                new BigDecimal("1935.00"),
                List.of(new ReceiptModel.Tender("CASH", new BigDecimal("1935.00"))),
                new BigDecimal("65.00"),
                null,
                "LKR",
                false,
                true);
    }

    private static void assertGolden(String name, String actual) throws IOException {
        String expected;
        try (InputStream in = ReceiptRendererTest.class.getResourceAsStream("/golden/" + name)) {
            Path out = Path.of("build", "golden-actual", name);
            Files.createDirectories(out.getParent());
            Files.writeString(out, actual);
            assertThat(in)
                    .as("golden file %s (actual written to %s)", name, out)
                    .isNotNull();
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertThat(actual).as("golden file %s", name).isEqualTo(expected);
    }

    /** ESC/POS bytes as hex, sixteen bytes a line, so a golden file can be read and diffed. */
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        HexFormat format = HexFormat.ofDelimiter(" ");
        for (int i = 0; i < bytes.length; i += 16) {
            out.append(format.formatHex(bytes, i, Math.min(bytes.length, i + 16)))
                    .append('\n');
        }
        return out.toString();
    }

    @Test
    void doc01doc02ThePreviewHoldsEveryPartOfTheReceiptAtBothWidths() throws IOException {
        assertGolden("receipt-58.txt", RENDERER.text(receipt(), template(58, true, "Lanka Mart")));
        assertGolden("receipt-80.txt", RENDERER.text(receipt().asCopy(), template(80, false, "Lanka Mart")));
    }

    @Test
    void doc01doc04EscPosPrintsTextCutsAndOpensTheDrawerAfterCash() throws IOException {
        byte[] bytes = RENDERER.escPos(receipt(), template(58, true, "Lanka Mart"));

        assertGolden("receipt-58.escpos.hex", hex(bytes));
        assertGolden(
                "receipt-80.escpos.hex", hex(RENDERER.escPos(receipt().asCopy(), template(80, false, "Lanka Mart"))));
        assertThat(hex(bytes)).startsWith("1b 40").endsWith("1b 70 00 19 fa\n");
        assertThat(hex(bytes)).contains("1d 56 42 00").as("the paper is cut").contains("1d 28 6b");
        ReceiptModel card = new ReceiptModel(
                BILL,
                "R1-000043",
                Instant.parse("2026-11-25T09:00:00Z"),
                null,
                null,
                receipt().lines(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1935.00"),
                List.of(new ReceiptModel.Tender("CARD", new BigDecimal("1935.00"))),
                BigDecimal.ZERO,
                "Hotel Lake View",
                "LKR",
                false,
                false);
        assertThat(hex(RENDERER.escPos(card, template(80, false, "Lanka Mart"))))
                .as("no cash, no drawer kick")
                .doesNotContain("1b 70 00 19 fa");
        assertThat(RENDERER.text(card, template(80, false, "Lanka Mart"))).contains("Customer: Hotel Lake View");
        assertThat(ReceiptRenderer.drawerKick()).containsExactly(0x1b, 0x70, 0x00, 0x19, 0xfa);
    }

    @Test
    void doc01APdfHoldsTheSameText() throws IOException {
        byte[] pdf = RENDERER.pdf(receipt().asCopy(), template(80, true, "Lanka Mart"));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("Lanka Mart", "*** COPY ***", "Bill R1-000042", "2026-11-25 14:30")
                    .contains("Karola loose", "0.35 kg x 2,400.00", "TOTAL LKR", "1,935.00", "Change", "65.00");
            assertThat(document.getPage(0).getMediaBox().getWidth())
                    .isCloseTo(226.77f, org.assertj.core.data.Offset.offset(0.1f));
        }
    }

    @Test
    void doc03SinhalaAndTamilAreSentAsRasterImagesAcrossThePaper() {
        ReceiptTemplate sinhala = template(58, false, "ලංකා මාර්ට්");
        byte[] bytes = RENDERER.escPos(receipt(), sinhala);
        String hex = hex(bytes).replace("\n", " ");

        assertThat(hex).as("GS v 0, 48 bytes a row on 58 mm paper").contains("1d 76 30 00 30 00");
        int at = indexOf(bytes, new byte[] {0x1d, 0x76, 0x30, 0x00});
        int rows = (bytes[at + 6] & 0xff) | (bytes[at + 7] & 0xff) << 8;
        assertThat(rows).isGreaterThan(GlyphRaster.TEXT_PIXELS);
        boolean ink = false;
        for (int i = at + 8; i < at + 8 + 48 * rows; i++) {
            ink |= bytes[i] != 0;
        }
        assertThat(ink).as("something was drawn").isTrue();
        assertThat(RENDERER.text(receipt(), sinhala)).contains("ලංකා මාර්ට්");
        assertThat(hex(RENDERER.escPos(receipt(), template(80, false, "தமிழ் கடை")))
                        .replace("\n", " "))
                .as("72 bytes a row on 80 mm paper")
                .contains("1d 76 30 00 48 00");
        assertThat(RENDERER.pdf(receipt(), sinhala)).isNotEmpty();
    }

    @Test
    void layoutWrapsLongNamesAndPutsWideTotalsBelow() {
        assertThat(ReceiptLayout.wrap("Soya meat chunks family pack extra large", 16))
                .containsExactly("Soya meat chunks", "family pack", "extra large");
        assertThat(ReceiptLayout.wrap("Supercalifragilisticexpialidocious", 10)).hasSize(4);
        assertThat(ReceiptLayout.pair("A long label here", "1,000,000.00", 20)).contains("\n");
        assertThat(ReceiptLayout.pair("Cash", "10.00", 12)).isEqualTo("Cash   10.00");
        assertThat(ReceiptLayout.qty(new BigDecimal("1.500"))).isEqualTo("1.5");
        assertThat(ReceiptLayout.qty(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThatThrownBy(() -> template(76, false, "Odd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GlyphRaster.withFonts(List.of(Path.of("build.gradle.kts"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        outer:
        for (int i = 0; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("pattern not found");
    }
}
