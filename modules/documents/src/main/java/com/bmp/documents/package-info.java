/**
 * Documents module: receipts rendered to ESC/POS, PDF or text, print agents at the shop's locations, and print jobs
 * for reprints and back-office prints (phase 03, DOC-01..DOC-06). It reads bills from sales and the receipt template
 * from tenant.
 */
@ApplicationModule(
        displayName = "Documents",
        allowedDependencies = {"tenant :: api", "sales :: api"})
@NullMarked
package com.bmp.documents;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
