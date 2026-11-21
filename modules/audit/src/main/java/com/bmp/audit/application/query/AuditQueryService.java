package com.bmp.audit.application.query;

import com.bmp.audit.domain.model.AuditRecord;
import com.bmp.audit.domain.port.AuditLedger;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the current tenant's audit trail, newest first, with a keyset cursor on the chain sequence. Records are immutable values, returned as they are. */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditLedger ledger;

    public AuditQueryService(AuditLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * Records matching every given filter.
     *
     * @param from inclusive lower bound of {@code occurredAt}
     * @param to exclusive upper bound of {@code occurredAt}
     */
    public PageResponse<AuditRecord> find(
            @Nullable String resourceType,
            @Nullable String resourceId,
            @Nullable Instant from,
            @Nullable Instant to,
            PageQuery query) {
        List<AuditRecord> page = ledger.find(resourceType, resourceId, from, to, before(query), query.fetchSize());
        return PageResponse.of(page, query, record -> List.of(Long.toString(record.sequence())));
    }

    private static long before(PageQuery query) {
        String cursor = query.cursor();
        if (cursor == null) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(CursorCodec.decode(cursor, 1).getFirst());
        } catch (NumberFormatException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
        }
    }
}
