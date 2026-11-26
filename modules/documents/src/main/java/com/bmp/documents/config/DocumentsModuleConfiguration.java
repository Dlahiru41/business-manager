package com.bmp.documents.config;

import com.bmp.persistence.migration.ModuleMigration;
import com.bmp.receipt.GlyphRaster;
import com.bmp.receipt.ReceiptRenderer;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the documents module with the platform: migrations, messages and the receipt renderer. */
@Configuration(proxyBeanMethods = false)
class DocumentsModuleConfiguration {

    /** After sync (100). */
    static final int MIGRATION_ORDER = 110;

    @Bean
    ModuleMigration documentsMigration() {
        return new ModuleMigration("documents", MIGRATION_ORDER);
    }

    @Bean
    ModuleMessages documentsMessages() {
        return new ModuleMessages("i18n/documents/messages");
    }

    /** DOC-03: Sinhala and Tamil are drawn with the machine's fonts (ADR-030). */
    @Bean
    ReceiptRenderer receiptRenderer() {
        return new ReceiptRenderer(GlyphRaster.systemFonts());
    }
}
