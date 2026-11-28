package com.bmp.finance.domain.model;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A fiscal period: the calendar month of the business date, written {@code YYYY-MM} (FIN-05). */
public record Period(YearMonth month) implements Comparable<Period> {

    private static final Pattern FORMAT = Pattern.compile("[0-9]{4}-(0[1-9]|1[0-2])");

    public Period {
        Objects.requireNonNull(month, "month");
    }

    public static Period of(LocalDate businessDate) {
        return new Period(YearMonth.from(businessDate));
    }

    /** Parses {@code YYYY-MM}; anything else is {@code INVALID_PERIOD}. */
    public static Period parse(String text) {
        if (!FORMAT.matcher(text).matches()) {
            throw invalid(text);
        }
        try {
            return new Period(YearMonth.parse(text));
        } catch (DateTimeParseException e) {
            throw invalid(text);
        }
    }

    public LocalDate firstDay() {
        return month.atDay(1);
    }

    public LocalDate lastDay() {
        return month.atEndOfMonth();
    }

    public Period next() {
        return new Period(month.plusMonths(1));
    }

    public Period previous() {
        return new Period(month.minusMonths(1));
    }

    /** Whether this is the last period of a fiscal year starting in {@code fiscalYearStartMonth} (1–12). */
    public boolean isYearEnd(int fiscalYearStartMonth) {
        return next().month().getMonthValue() == fiscalYearStartMonth;
    }

    /** The first period of the fiscal year this period belongs to. */
    public Period fiscalYearStart(int fiscalYearStartMonth) {
        YearMonth start = YearMonth.of(month.getYear(), fiscalYearStartMonth);
        return new Period(start.isAfter(month) ? start.minusYears(1) : start);
    }

    @Override
    public int compareTo(Period other) {
        return month.compareTo(other.month);
    }

    @Override
    public String toString() {
        return month.toString();
    }

    private static BusinessRuleViolation invalid(String text) {
        return new BusinessRuleViolation(FinanceErrorCode.INVALID_PERIOD, Map.of("period", text));
    }
}
