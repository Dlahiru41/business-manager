package com.bmp.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BusinessDateTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    @ParameterizedTest
    @CsvSource({
        // instant (UTC),        cutoff, expected business date (Colombo is UTC+05:30)
        "2026-11-20T18:29:59Z, 0, 2026-11-20",
        "2026-11-20T18:30:00Z, 0, 2026-11-21",
        "2026-11-20T20:00:00Z, 4, 2026-11-20",
        "2026-11-20T22:30:00Z, 4, 2026-11-21",
        "2026-11-21T17:29:00Z, 23, 2026-11-20"
    })
    void shouldUseTenantZoneAndCutoffHour(String instant, int cutoff, String expected) {
        BusinessDate date = BusinessDate.of(Instant.parse(instant), COLOMBO, cutoff);

        assertThat(date.date()).isEqualTo(LocalDate.parse(expected));
        assertThat(date).hasToString(expected);
    }

    @Test
    void shouldRejectInvalidInput() {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");

        assertThatIllegalArgumentException().isThrownBy(() -> BusinessDate.of(now, COLOMBO, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessDate.of(now, COLOMBO, 24));
        assertThatNullPointerException().isThrownBy(() -> BusinessDate.of(null, COLOMBO, 0));
        assertThatNullPointerException().isThrownBy(() -> BusinessDate.of(now, null, 0));
        assertThatNullPointerException().isThrownBy(() -> new BusinessDate(null));
    }

    @Test
    void shouldOrderChronologically() {
        BusinessDate earlier = new BusinessDate(LocalDate.parse("2026-11-20"));
        BusinessDate later = new BusinessDate(LocalDate.parse("2026-11-21"));

        assertThat(earlier).isLessThan(later);
    }
}
