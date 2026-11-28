package com.bmp.finance.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PeriodTest {

    @Test
    void fin05APeriodIsTheMonthOfTheBusinessDate() {
        Period november = Period.of(LocalDate.parse("2026-11-30"));

        assertThat(november).isEqualTo(Period.parse("2026-11"));
        assertThat(november.toString()).isEqualTo("2026-11");
        assertThat(november.firstDay()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(november.lastDay()).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(november.next()).isEqualTo(Period.parse("2026-12"));
        assertThat(november.previous()).isEqualTo(Period.parse("2026-10"));
        assertThat(november.next().next()).isEqualTo(Period.parse("2027-01"));
        assertThat(november).isLessThan(november.next()).isGreaterThan(november.previous());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026-00", "2026-1", "26-11", "2026/11", "november", ""})
    void aMalformedPeriodIsInvalid(String text) {
        assertThatThrownBy(() -> Period.parse(text)).isInstanceOfSatisfying(BusinessRuleViolation.class, e -> {
            assertThat(e.errorCode()).isEqualTo(FinanceErrorCode.INVALID_PERIOD);
            assertThat(e.details()).containsEntry("period", text);
        });
    }

    @ParameterizedTest
    @CsvSource({"2026-12, 1, true", "2026-11, 1, false", "2027-03, 4, true", "2026-12, 4, false", "2026-01, 2, true"})
    void fin06TheYearEndIsTheMonthBeforeTheFiscalYearStarts(String period, int start, boolean yearEnd) {
        assertThat(Period.parse(period).isYearEnd(start)).isEqualTo(yearEnd);
    }

    @ParameterizedTest
    @CsvSource({"2026-02, 4, 2025-04", "2026-05, 4, 2026-04", "2026-04, 4, 2026-04", "2026-11, 1, 2026-01"})
    void theFiscalYearStartsInItsMonth(String period, int start, String first) {
        assertThat(Period.parse(period).fiscalYearStart(start)).isEqualTo(Period.parse(first));
    }

    @Test
    void fin06APeriodClosesOnceItHasEnded() {
        FiscalPeriod november = FiscalPeriod.open(Period.parse("2026-11"));

        assertThat(november.acceptsPostings()).isTrue();
        assertThatThrownBy(() -> november.startClosing(LocalDate.parse("2026-11-30")))
                .isInstanceOfSatisfying(BusinessRuleViolation.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(FinanceErrorCode.PERIOD_NOT_ENDED);
                    assertThat(e.details()).containsEntry("lastDay", "2026-11-30");
                });

        FiscalPeriod closing = november.startClosing(LocalDate.parse("2026-12-01"));
        assertThat(closing.status()).isEqualTo(PeriodStatus.CLOSING);
        assertThat(closing.acceptsPostings()).isFalse();
        assertThat(code(() -> closing.startClosing(LocalDate.parse("2026-12-02"))))
                .isEqualTo(FinanceErrorCode.PERIOD_CLOSE_IN_PROGRESS);

        Instant at = Instant.parse("2026-12-02T08:00:00Z");
        UUID by = UUID.randomUUID();
        FiscalPeriod closed = closing.closed(at, by);
        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closed.closedAt()).isEqualTo(at);
        assertThat(closed.closedBy()).isEqualTo(by);
        assertThat(closed.acceptsPostings()).isFalse();
        assertThat(code(() -> closed.startClosing(LocalDate.parse("2026-12-03"))))
                .isEqualTo(FinanceErrorCode.PERIOD_ALREADY_CLOSED);
        assertThat(code(() -> closed.closed(at, by))).isEqualTo(FinanceErrorCode.PERIOD_ALREADY_CLOSED);
        assertThat(code(() -> november.closed(at, by))).isEqualTo(FinanceErrorCode.PERIOD_ALREADY_CLOSED);
    }

    @Test
    void fin06AFailedCloseReopensThePeriod() {
        FiscalPeriod closing = FiscalPeriod.open(Period.parse("2026-11")).startClosing(LocalDate.parse("2026-12-05"));

        assertThat(closing.reopened()).isEqualTo(FiscalPeriod.open(Period.parse("2026-11")));
        FiscalPeriod closed = closing.closed(Instant.EPOCH, null);
        assertThat(closed.reopened()).isSameAs(closed);
    }

    private static com.bmp.shared.error.ErrorCode code(Runnable action) {
        try {
            action.run();
        } catch (BusinessRuleViolation e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a refusal");
    }
}
