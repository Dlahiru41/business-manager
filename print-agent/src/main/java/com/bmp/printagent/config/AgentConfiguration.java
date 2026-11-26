package com.bmp.printagent.config;

import com.bmp.printagent.cloud.CloudClient;
import com.bmp.printagent.printer.Printer;
import com.bmp.receipt.GlyphRaster;
import com.bmp.receipt.ReceiptRenderer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import tools.jackson.databind.json.JsonMapper;

/** The printer, the renderer and the cloud client built from {@link AgentProperties}. */
@Configuration(proxyBeanMethods = false)
class AgentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Printer printer(AgentProperties settings) {
        return Printer.of(settings.printer());
    }

    /** Sinhala and Tamil rows are drawn with the fonts installed on the shop PC (ADR-030 §1). */
    @Bean
    ReceiptRenderer receiptRenderer() {
        return new ReceiptRenderer(GlyphRaster.systemFonts());
    }

    /** Built only when the cloud link starts, so an agent without cloud settings runs for the till alone. */
    @Bean
    @Lazy
    CloudClient cloudClient(AgentProperties settings, JsonMapper json, Clock clock) {
        return new CloudClient(settings.cloud(), json, clock);
    }
}
