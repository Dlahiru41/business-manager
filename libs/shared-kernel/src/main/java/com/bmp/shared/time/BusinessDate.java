package com.bmp.shared.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * The tenant-local date a transaction belongs to. With a cut-off hour {@code h}, everything before {@code h:00}
 * local time still belongs to the previous business day (a shop open past midnight).
 *
 * @param date the business date
 */
public record BusinessDate(LocalDate date) implements Comparable<BusinessDate> {

    public static final int MAX_CUTOFF_HOUR = 23;

    public BusinessDate {
        Objects.requireNonNull(date, "date");
    }

    /**
     * Computes the business date of an instant.
     *
     * @param instant when it happened (UTC)
     * @param zone the tenant's time zone
     * @param cutoffHour 0–23; 0 means the business day equals the calendar day
     */
    public static BusinessDate of(Instant instant, ZoneId zone, int cutoffHour) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        if (cutoffHour < 0 || cutoffHour > MAX_CUTOFF_HOUR) {
            throw new IllegalArgumentException("cutoffHour must be between 0 and 23: " + cutoffHour);
        }
        ZonedDateTime local = instant.atZone(zone);
        LocalDate date = local.toLocalDate();
        return new BusinessDate(local.getHour() < cutoffHour ? date.minusDays(1) : date);
    }

    @Override
    public int compareTo(BusinessDate other) {
        return date.compareTo(other.date);
    }

    @Override
    public String toString() {
        return date.toString();
    }
}
