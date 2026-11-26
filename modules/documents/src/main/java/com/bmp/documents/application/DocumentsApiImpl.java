package com.bmp.documents.application;

import com.bmp.documents.api.DocumentsQueries;
import com.bmp.documents.api.dto.PrintTemplate;
import com.bmp.receipt.ReceiptTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The documents API for other modules. */
@Component
class DocumentsApiImpl implements DocumentsQueries {

    private final ReceiptSource receipts;

    DocumentsApiImpl(ReceiptSource receipts) {
        this.receipts = receipts;
    }

    @Override
    @Transactional(readOnly = true)
    public PrintTemplate receiptTemplate() {
        ReceiptTemplate t = receipts.template(null);
        return new PrintTemplate(
                t.version(),
                t.shopName(),
                t.addressLines(),
                t.phone(),
                t.taxNumber(),
                t.footerLines(),
                t.paperWidthMm(),
                t.showQr(),
                t.openDrawerOnCash(),
                t.language());
    }
}
