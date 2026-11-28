package com.bmp.finance.application.query;

import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.port.LedgerReader;
import com.bmp.finance.domain.port.LedgerReader.ClearingDifference;
import com.bmp.payments.api.PaymentQueries;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import com.bmp.persistence.event.FailedEventStore;
import com.bmp.tenant.api.dto.TenantSummary;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule FIN-06: what stands in the way of closing a period — parked failed events of the tenant, bills whose POS
 * clearing is not zero (unless acknowledged) and drawer sessions opened in the period that are not reconciled.
 */
@Component
public class CloseChecks {

    private final FailedEventStore failedEvents;
    private final LedgerReader reader;
    private final PaymentQueries payments;
    private final FinanceSettings settings;

    public CloseChecks(
            FailedEventStore failedEvents, LedgerReader reader, PaymentQueries payments, FinanceSettings settings) {
        this.failedEvents = failedEvents;
        this.reader = reader;
        this.payments = payments;
        this.settings = settings;
    }

    /** The checks of a period; {@code blocking} is the first that fails. */
    public record Result(
            Period period,
            long failedEvents,
            List<ClearingDifference> differences,
            List<DrawerSessionInfo> unreconciled,
            List<ClearingDifference> unacknowledged) {

        public Result {
            differences = List.copyOf(differences);
            unreconciled = List.copyOf(unreconciled);
            unacknowledged = List.copyOf(unacknowledged);
        }

        public boolean ready() {
            return failedEvents == 0 && unacknowledged.isEmpty() && unreconciled.isEmpty();
        }

        public FinanceErrorCode blocking() {
            if (failedEvents > 0) {
                return FinanceErrorCode.FAILED_EVENTS_PENDING;
            }
            return unacknowledged.isEmpty()
                    ? FinanceErrorCode.DRAWERS_NOT_RECONCILED
                    : FinanceErrorCode.POS_CLEARING_NOT_ZERO;
        }

        public Map<String, Object> details() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("period", period.toString());
            details.put(
                    "count",
                    switch (blocking()) {
                        case FAILED_EVENTS_PENDING -> failedEvents;
                        case POS_CLEARING_NOT_ZERO -> unacknowledged.size();
                        default -> unreconciled.size();
                    });
            return details;
        }
    }

    @Transactional(readOnly = true)
    public Result of(Period period, Collection<UUID> acknowledgedBillIds) {
        List<ClearingDifference> differences = reader.posClearingDifferences(period);
        TenantSummary tenant = settings.tenant();
        ZoneId zone = ZoneId.of(tenant.timeZone());
        Instant from = period.firstDay()
                .atTime(tenant.businessDayCutoffHour(), 0)
                .atZone(zone)
                .toInstant();
        Instant to = period.next()
                .firstDay()
                .atTime(tenant.businessDayCutoffHour(), 0)
                .atZone(zone)
                .toInstant();
        return new Result(
                period,
                failedEvents.parkedCount(),
                differences,
                payments.unreconciledOpenedBetween(from, to),
                differences.stream()
                        .filter(d -> !acknowledgedBillIds.contains(d.billId()))
                        .toList());
    }
}
