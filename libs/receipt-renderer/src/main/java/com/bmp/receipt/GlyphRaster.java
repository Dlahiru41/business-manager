package com.bmp.receipt;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws text the printer has no font for (Sinhala, Tamil) into a one-bit image as wide as the paper (DOC-03). The font
 * is the first that can show the whole text: the fonts given (for instance Noto Sans Sinhala and Noto Sans Tamil
 * shipped with the print agent), then the machine's own fonts (Windows has Nirmala UI), then the JDK's sans serif.
 */
public final class GlyphRaster {

    /** Pixels of text height on a 203 dpi printer: about 3 mm, the size of the printer's own font. */
    static final int TEXT_PIXELS = 24;

    private final List<Font> fonts;
    private final Map<Character.UnicodeScript, Font> byScript = new ConcurrentHashMap<>();

    private GlyphRaster(List<Font> fonts) {
        this.fonts = List.copyOf(fonts);
    }

    /** Only the machine's fonts. */
    public static GlyphRaster systemFonts() {
        return new GlyphRaster(List.of());
    }

    /** The given TrueType or OpenType files first, then the machine's fonts. */
    public static GlyphRaster withFonts(List<Path> files) {
        List<Font> fonts = new ArrayList<>();
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                fonts.add(Font.createFont(Font.TRUETYPE_FONT, in));
            } catch (IOException | java.awt.FontFormatException e) {
                throw new IllegalArgumentException("Not a usable font: " + file, e);
            }
        }
        return new GlyphRaster(fonts);
    }

    /** A one-bit image of {@code text}, {@code width} pixels wide; black is drawn ink. */
    BufferedImage draw(String text, int width, boolean centered, boolean large) {
        Font font = fontFor(text).deriveFont(Font.PLAIN, (float) (large ? TEXT_PIXELS * 1.5 : TEXT_PIXELS));
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D measure = probe.createGraphics();
        FontMetrics metrics = measure.getFontMetrics(font);
        measure.dispose();
        int height = metrics.getHeight() + 2;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setFont(font);
        int x = centered ? Math.max(0, (width - metrics.stringWidth(text)) / 2) : 0;
        g.drawString(text, x, metrics.getAscent() + 1);
        g.dispose();
        return image;
    }

    Font fontFor(String text) {
        for (Font font : fonts) {
            if (font.canDisplayUpTo(text) == -1) {
                return font;
            }
        }
        Character.UnicodeScript script = script(text);
        return byScript.computeIfAbsent(script, s -> systemFont(text));
    }

    private static Font systemFont(String text) {
        for (Font font : GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts()) {
            if (font.canDisplayUpTo(text) == -1) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, TEXT_PIXELS);
    }

    /** The first script of the text that is not common punctuation, digits or Latin. */
    private static Character.UnicodeScript script(String text) {
        return text.codePoints()
                .mapToObj(Character.UnicodeScript::of)
                .filter(s -> s != Character.UnicodeScript.COMMON
                        && s != Character.UnicodeScript.LATIN
                        && s != Character.UnicodeScript.INHERITED)
                .findFirst()
                .orElse(Character.UnicodeScript.LATIN);
    }

    /** The image as {@code GS v 0} raster data: rows of bytes, the most significant bit first, 1 for ink. */
    static byte[] rasterBits(BufferedImage image) {
        int bytesPerRow = (image.getWidth() + 7) / 8;
        byte[] data = new byte[bytesPerRow * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                boolean ink = (image.getRGB(x, y) & 0xFFFFFF) == 0;
                if (ink) {
                    data[y * bytesPerRow + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        return data;
    }
}
