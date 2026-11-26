package com.bmp.documents.api;

import com.bmp.documents.api.dto.PrintTemplate;

/** What other modules read from documents. */
public interface DocumentsQueries {

    /**
     * The receipt template of the current tenant at its own paper width, as print agents get it (DOC-06): tills keep
     * it to print offline.
     */
    PrintTemplate receiptTemplate();
}
