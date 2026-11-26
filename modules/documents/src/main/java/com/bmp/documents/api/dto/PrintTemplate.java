package com.bmp.documents.api.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A receipt template as documents.yaml {@code ReceiptTemplate} describes it (DOC-06).
 *
 * @param version the tenant template's version; 0 also when the tenant has none and its name heads the receipt
 * @param paperWidthMm 58 or 80
 * @param openDrawerOnCash whether the cash drawer opens after a cash tender (DOC-04)
 * @param language {@code en}, {@code si} or {@code ta}
 */
public record PrintTemplate(
        long version,
        String shopName,
        List<String> addressLines,
        @Nullable String phone,
        @Nullable String taxNumber,
        List<String> footerLines,
        int paperWidthMm,
        boolean showQr,
        boolean openDrawerOnCash,
        String language) {

    public PrintTemplate {
        addressLines = List.copyOf(addressLines);
        footerLines = List.copyOf(footerLines);
    }
}
