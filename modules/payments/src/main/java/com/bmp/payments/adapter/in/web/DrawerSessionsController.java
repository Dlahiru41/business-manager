package com.bmp.payments.adapter.in.web;

import com.bmp.payments.adapter.in.web.generated.api.DrawerSessionsApi;
import com.bmp.payments.adapter.in.web.generated.model.CashMovementRequest;
import com.bmp.payments.adapter.in.web.generated.model.CountDrawerRequest;
import com.bmp.payments.adapter.in.web.generated.model.DrawerSession;
import com.bmp.payments.adapter.in.web.generated.model.DrawerSessionPage;
import com.bmp.payments.adapter.in.web.generated.model.DrawerStatus;
import com.bmp.payments.adapter.in.web.generated.model.ExpectedCash;
import com.bmp.payments.adapter.in.web.generated.model.OpenDrawerRequest;
import com.bmp.payments.adapter.in.web.generated.model.ReconcileDrawerRequest;
import com.bmp.payments.application.command.DrawerHandlers;
import com.bmp.payments.application.query.PaymentsQueryService;
import com.bmp.payments.domain.model.CashMovementType;
import com.bmp.web.etag.Etags;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Cash drawers from float to reconciliation (PAY-04). */
@RestController
class DrawerSessionsController implements DrawerSessionsApi {

    private final DrawerHandlers handlers;
    private final PaymentsQueryService queries;
    private final PaymentsWebMapper mapper;

    DrawerSessionsController(DrawerHandlers handlers, PaymentsQueryService queries, PaymentsWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'payments:read')")
    public ResponseEntity<DrawerSessionPage> listDrawerSessions(
            @Nullable UUID registerId, @Nullable DrawerStatus status, Integer limit, @Nullable String cursor) {
        var page = queries.sessions(
                registerId,
                status == null ? null : com.bmp.payments.domain.model.DrawerStatus.valueOf(status.getValue()),
                PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new DrawerSessionPage()
                .items(page.items().stream().map(mapper::session).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'drawer:operate-own')")
    public ResponseEntity<DrawerSession> openDrawerSession(UUID idempotencyKey, OpenDrawerRequest request) {
        var session = handlers.open(
                request.getId(),
                request.getRegisterId(),
                new BigDecimal(request.getOpeningFloat()),
                PaymentsWebMapper.instant(request.getOpenedAt()),
                null);
        return ResponseEntity.created(URI.create("/api/v1/drawer-sessions/" + session.id()))
                .eTag(Etags.of(session.version()))
                .body(mapper.session(queries.session(session.id())));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'payments:read')")
    public ResponseEntity<DrawerSession> getDrawerSession(UUID sessionId) {
        var detail = queries.session(sessionId);
        return ResponseEntity.ok().eTag(Etags.of(detail.session().version())).body(mapper.session(detail));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'payments:read')")
    public ResponseEntity<ExpectedCash> getExpectedCash(UUID sessionId) {
        return ResponseEntity.ok(mapper.expected(queries.session(sessionId)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'drawer:operate-own')")
    public ResponseEntity<DrawerSession> recordCashMovement(
            UUID sessionId, UUID idempotencyKey, CashMovementRequest request) {
        handlers.record(
                sessionId,
                request.getId(),
                CashMovementType.valueOf(request.getType().getValue()),
                new BigDecimal(request.getAmount()),
                request.getReason(),
                PaymentsWebMapper.instant(request.getOccurredAt()),
                true);
        return ResponseEntity.created(URI.create("/api/v1/drawer-sessions/" + sessionId))
                .body(mapper.session(queries.session(sessionId)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'drawer:operate-own')")
    public ResponseEntity<DrawerSession> countDrawer(UUID sessionId, UUID idempotencyKey, CountDrawerRequest request) {
        var session = handlers.count(
                sessionId,
                request.getDenominations().stream().map(mapper::denomination).toList(),
                null,
                true);
        return ResponseEntity.ok().eTag(Etags.of(session.version())).body(mapper.session(queries.session(sessionId)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'payments:reconcile')")
    public ResponseEntity<DrawerSession> reconcileDrawer(
            UUID sessionId, UUID idempotencyKey, ReconcileDrawerRequest request) {
        var session = handlers.reconcile(sessionId, request.getNote());
        return ResponseEntity.ok().eTag(Etags.of(session.version())).body(mapper.session(queries.session(sessionId)));
    }
}
