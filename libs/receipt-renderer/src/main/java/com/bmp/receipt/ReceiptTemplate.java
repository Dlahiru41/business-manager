package com.bmp.receipt;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * How a shop's receipts look (DOC-02, DOC-06): versioned by the tenant, cached by print agents.
 *
 * @param paperWidthMm 58 (32 characters, 384 dots) or 80 (48 characters, 576 dots)
 * @param timeZone the tenant's, for the receipt's date and time
 */
public record ReceiptTemplate(
        long version,
        String shopName,
        List<String> addressLines,
        @Nullable String phone,
        @Nullable String taxNumber,
        List<String> footerLines,
        int paperWidthMm,
        boolean showQr,
        boolean openDrawerOnCash,
        String language,
        ZoneId timeZone) {

    public ReceiptTemplate {
        Objects.requireNonNull(shopName, "shopName");
        addressLines = List.copyOf(addressLines);
        footerLines = List.copyOf(footerLines);
        if (paperWidthMm != 58 && paperWidthMm != 80) {
            throw new IllegalArgumentException("Receipts are 58 or 80 mm wide, not " + paperWidthMm);
        }
    }

    /** Characters per line in the printer's standard font. */
    public int columns() {
        return paperWidthMm == 58 ? 32 : 48;
    }

    /** Printable dots per line at 203 dpi. */
    public int dots() {
        return paperWidthMm == 58 ? 384 : 576;
    }
}
