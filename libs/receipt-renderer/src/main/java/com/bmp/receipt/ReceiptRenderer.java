package com.bmp.receipt;

import com.bmp.receipt.ReceiptLayout.Align;
import com.bmp.receipt.ReceiptLayout.Row;
import com.bmp.receipt.ReceiptLayout.Style;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Renders a receipt (DOC-01..DOC-04) to ESC/POS bytes for 58 and 80 mm thermal printers, to a PDF, or to plain text.
 * Plain ASCII prints in the printer's text mode; other text (Sinhala, Tamil) is drawn and sent as a raster image.
 */
public final class ReceiptRenderer {

    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;
    private static final float MM = 72f / 25.4f;
    private static final float MARGIN = 4f;

    private final GlyphRaster raster;

    public ReceiptRenderer(GlyphRaster raster) {
        this.raster = raster;
    }

    /** Plain text, as a preview (DOC-01 c); non-ASCII text is kept as it is. */
    public String text(ReceiptModel model, ReceiptTemplate template) {
        List<String> lines = new ArrayList<>();
        int width = template.columns();
        for (Row row : ReceiptLayout.rows(model, template)) {
            for (String part : row.qr()
                    ? List.of("[QR " + row.text() + "]")
                    : List.of(row.text().split("\n", -1))) {
                lines.add(row.align() == Align.CENTER ? center(part, width) : part);
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /** ESC/POS bytes (DOC-01 a, DOC-03, DOC-04): text mode, raster for other scripts, QR, cut and drawer kick. */
    public byte[] escPos(ReceiptModel model, ReceiptTemplate template) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {ESC, '@'});
        for (Row row : ReceiptLayout.rows(model, template)) {
            out.writeBytes(new byte[] {ESC, 'a', (byte) (row.align() == Align.CENTER ? 1 : 0)});
            if (row.qr()) {
                qr(out, row.text());
            } else if (row.plain()) {
                styled(out, row);
            } else {
                for (String part : row.text().split("\n", -1)) {
                    image(
                            out,
                            raster.draw(
                                    part, template.dots(), row.align() == Align.CENTER, row.style() == Style.LARGE));
                }
            }
        }
        out.writeBytes(new byte[] {ESC, 'a', 0, ESC, 'd', 4, GS, 'V', 66, 0});
        if (template.openDrawerOnCash() && model.cashTendered()) {
            out.writeBytes(drawerKick());
        }
        return out.toByteArray();
    }

    /** {@code ESC p 0 25 250}: pulse the cash drawer (DOC-04). */
    public static byte[] drawerKick() {
        return new byte[] {ESC, 'p', 0, 25, (byte) 250};
    }

    /** A PDF as wide as the paper (DOC-01 b); text in Courier, other scripts as images. */
    public byte[] pdf(ReceiptModel model, ReceiptTemplate template) {
        List<Row> rows = ReceiptLayout.rows(model, template);
        float width = template.paperWidthMm() * MM;
        float size = (width - 2 * MARGIN) / (0.6f * template.columns());
        float leading = size * 1.25f;
        int lines = rows.stream()
                .mapToInt(r -> r.qr() ? 1 : r.text().split("\n", -1).length)
                .sum();
        float height = 2 * MARGIN + lines * leading * 1.5f;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            PDType1Font courier = new PDType1Font(Standard14Fonts.FontName.COURIER);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = height - MARGIN - leading;
                for (Row row : rows) {
                    for (String part :
                            row.qr() ? List.of(row.text()) : List.of(row.text().split("\n", -1))) {
                        if (row.plain() || row.qr()) {
                            String line = row.align() == Align.CENTER ? center(part, template.columns()) : part;
                            content.beginText();
                            content.setFont(row.style() == Style.NORMAL ? courier : bold, size);
                            content.newLineAtOffset(MARGIN, y);
                            content.showText(line);
                            content.endText();
                            y -= leading;
                        } else {
                            BufferedImage image = raster.draw(
                                    part, template.dots(), row.align() == Align.CENTER, row.style() == Style.LARGE);
                            PDImageXObject drawn = LosslessFactory.createFromImage(document, image);
                            float drawnHeight = (width - 2 * MARGIN) * image.getHeight() / image.getWidth();
                            y -= drawnHeight - leading;
                            content.drawImage(drawn, MARGIN, y, width - 2 * MARGIN, drawnHeight);
                            y -= leading;
                        }
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The receipt could not be written as a PDF", e);
        }
    }

    private static void styled(ByteArrayOutputStream out, Row row) {
        boolean bold = row.style() != Style.NORMAL;
        boolean large = row.style() == Style.LARGE;
        if (bold) {
            out.writeBytes(new byte[] {ESC, 'E', 1});
        }
        if (large) {
            out.writeBytes(new byte[] {GS, '!', 0x11});
        }
        for (String part : row.text().split("\n", -1)) {
            out.writeBytes(part.getBytes(StandardCharsets.US_ASCII));
            out.write('\n');
        }
        if (large) {
            out.writeBytes(new byte[] {GS, '!', 0});
        }
        if (bold) {
            out.writeBytes(new byte[] {ESC, 'E', 0});
        }
    }

    /** {@code GS v 0}: a raster image, one bit per dot (DOC-03). */
    private static void image(ByteArrayOutputStream out, BufferedImage image) {
        int bytesPerRow = (image.getWidth() + 7) / 8;
        int rows = image.getHeight();
        out.writeBytes(new byte[] {
            GS,
            'v',
            '0',
            0,
            (byte) (bytesPerRow & 0xFF),
            (byte) (bytesPerRow >> 8),
            (byte) (rows & 0xFF),
            (byte) (rows >> 8)
        });
        out.writeBytes(GlyphRaster.rasterBits(image));
    }

    /** {@code GS ( k}: a QR code, model 2, error correction M (DOC-02). */
    private static void qr(ByteArrayOutputStream out, String text) {
        byte[] data = text.getBytes(StandardCharsets.US_ASCII);
        int length = data.length + 3;
        out.writeBytes(new byte[] {GS, '(', 'k', 4, 0, '1', 'A', '2', 0});
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, '1', 'C', 6});
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, '1', 'E', '1'});
        out.writeBytes(new byte[] {GS, '(', 'k', (byte) (length & 0xFF), (byte) (length >> 8), '1', 'P', '0'});
        out.writeBytes(data);
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, '1', 'Q', '0'});
        out.write('\n');
    }

    private static String center(String text, int width) {
        int pad = Math.max(0, (width - text.length()) / 2);
        return " ".repeat(pad) + text;
    }
}
