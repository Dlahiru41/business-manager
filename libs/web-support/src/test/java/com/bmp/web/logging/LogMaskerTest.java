package com.bmp.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.bmp.web.request.ModuleMdcInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogMaskerTest {

    private static final String JWT =
            "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwidGVuYW50X2lkIjoiYSJ9.c2lnbmF0dXJlLXZhbHVl";

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "jwt            | token issued " + JWT + " ok           | token issued eyJ*** ok",
                "bearer header  | Authorization: Bearer abc.def-ghi_123456 | Authorization: Bearer ***",
                "password kv    | login password=hunter2 user=42        | login password=*** user=42",
                "secret json    | {\"client_secret\": \"s3cr3t-value\"}  | {\"client_secret\": \"***\"}",
                "card digits    | card 4111111111111111 declined        | card ***PAN*** declined",
                "card grouped   | card 4111 1111 1111 1111 declined     | card ***PAN*** declined",
                "email          | sent to nimal.perera@example.lk now  | sent to ***@*** now",
                "phone intl     | call +94 77 123 4567 today            | call +94*** today",
                "phone compact  | call +94771234567 today               | call +94*** today",
                "phone local    | call 0771234567 today                 | call 07*** today"
            })
    void shouldMaskSecretsAndPersonalData(String name, String raw, String expected) {
        assertThat(LogMasker.mask(raw.strip())).isEqualTo(expected.strip());
    }

    @Test
    void shouldKeepIdsAmountsAndPlainText() {
        String line = "Bill completed billId=0193a6f1-0000-7000-8000-000000000001 total=1250.00 lines=3";

        assertThat(LogMasker.mask(line)).isEqualTo(line);
        assertThat(LogMasker.mask("")).isEmpty();
    }

    @Test
    void converterShouldMaskFormattedMessages() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent(
                "fqcn", logger, Level.INFO, "customer email {}", null, new Object[] {"a.b@example.com"});
        MaskingMessageConverter converter = new MaskingMessageConverter();

        assertThat(converter.convert(event)).isEqualTo("customer email ***@***");
    }

    @Test
    void shouldDeriveModuleFromHandlerPackage() {
        assertThat(ModuleMdcInterceptor.moduleOf("com.bmp.tenant.adapter.in.web.TenantController"))
                .isEqualTo("tenant");
        assertThat(ModuleMdcInterceptor.moduleOf("org.example.Other")).isNull();
    }
}
