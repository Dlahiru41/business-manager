package com.bmp.reporting.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.reporting.domain.error.ReportingErrorCode;
import com.bmp.shared.error.ErrorCategory;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportingDomainTest {

    @Test
    void theErrorCodesNameTheirCategoryAndModule() {
        assertThat(ReportingErrorCode.BILL_NOT_REPORTED.category()).isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(ReportingErrorCode.INVALID_DATE_RANGE.category()).isEqualTo(ErrorCategory.VALIDATION);
        assertThat(ReportingErrorCode.EXPORT_LINK_INVALID.category()).isEqualTo(ErrorCategory.FORBIDDEN);
        assertThat(ReportingErrorCode.EXPORT_NOT_FOUND.code()).isEqualTo("EXPORT_NOT_FOUND");
        assertThat(ReportingErrorCode.OPERATION_NOT_FOUND.module()).isEqualTo("reporting");
    }

    @Test
    void rep02AnEntryIdFollowsWhatHappenedToTheLine() {
        UUID line = UUID.randomUUID();
        UUID note = UUID.randomUUID();

        assertThat(Entries.sold(line)).isEqualTo(line);
        assertThat(Entries.returned(note, line))
                .isEqualTo(Entries.returned(note, line))
                .isNotEqualTo(line);
        assertThat(Entries.returned(UUID.randomUUID(), line)).isNotEqualTo(Entries.returned(note, line));
        assertThat(Entries.voided(line)).isEqualTo(Entries.voided(line)).isNotEqualTo(line);
        assertThat(Entries.ofCost("SALE", line, line)).isEqualTo(line);
        assertThat(Entries.ofCost("SALE_RETURN", note, line)).isEqualTo(Entries.returned(note, line));
        assertThat(Entries.ofCost("SALE_VOID", UUID.randomUUID(), line)).isEqualTo(Entries.voided(line));
    }

    @Test
    void rep02MarginIsRevenueLessCostAndItsPercentOfRevenue() {
        assertThat(Margins.margin(new BigDecimal("26000.00"), new BigDecimal("21180.00")))
                .isEqualByComparingTo("4820.00");
        assertThat(Margins.percent(new BigDecimal("26000.00"), new BigDecimal("21180.00")))
                .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("18.54"));
        assertThat(Margins.percent(new BigDecimal("3"), new BigDecimal("2")))
                .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("33.33"));
        assertThat(Margins.percent(new BigDecimal("8"), new BigDecimal("7")))
                .hasValueSatisfying(p -> assertThat(p.scale()).isEqualTo(2));
        assertThat(Margins.percent(BigDecimal.ZERO, BigDecimal.TEN)).isEmpty();
        assertThat(Margins.percent(new BigDecimal("-100"), new BigDecimal("50")))
                .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("150.00"));
    }
}
