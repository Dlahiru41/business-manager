package com.bmp.documents.application;

import com.bmp.documents.domain.model.PrintAgent;
import com.bmp.documents.domain.port.PrintAgentRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Print agents of the tenant. */
@Service
public class PrintAgentQueries {

    private final PrintAgentRepository agents;

    public PrintAgentQueries(PrintAgentRepository agents) {
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public PrintAgent agent(UUID id) {
        return agents.findById(id).orElseThrow(() -> DocumentsHandlers.agentNotFound(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PrintAgent> agents(@Nullable UUID locationId, PageQuery query) {
        UUID before = null;
        if (query.cursor() != null) {
            try {
                before = UUID.fromString(CursorCodec.decode(Objects.requireNonNull(query.cursor()), 1)
                        .getFirst());
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleViolation(
                        PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
            }
        }
        return PageResponse.of(
                agents.page(locationId, before, query.fetchSize()), query, a -> List.of(a.id().toString()));
    }
}
