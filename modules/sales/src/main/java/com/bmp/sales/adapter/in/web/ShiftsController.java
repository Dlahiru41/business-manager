package com.bmp.sales.adapter.in.web;

import com.bmp.sales.adapter.in.web.generated.api.ShiftsApi;
import com.bmp.sales.adapter.in.web.generated.model.CloseShiftRequest;
import com.bmp.sales.adapter.in.web.generated.model.OpenShiftRequest;
import com.bmp.sales.adapter.in.web.generated.model.Shift;
import com.bmp.sales.application.command.ShiftHandlers;
import com.bmp.web.etag.Etags;
import com.bmp.web.idempotency.Idempotent;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Shifts (SAL-12). */
@RestController
class ShiftsController implements ShiftsApi {

    private final ShiftHandlers handlers;
    private final SalesWebMapper mapper;

    ShiftsController(ShiftHandlers handlers, SalesWebMapper mapper) {
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:create')")
    public ResponseEntity<Shift> openShift(UUID idempotencyKey, OpenShiftRequest request) {
        var shift = handlers.open(
                request.getId(), request.getRegisterId(), SalesWebMapper.instant(request.getOpenedAt()), null);
        return ResponseEntity.created(URI.create("/api/v1/shifts/" + shift.id()))
                .eTag(Etags.of(shift.version()))
                .body(mapper.shift(shift));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:create')")
    public ResponseEntity<Shift> closeShift(UUID shiftId, UUID idempotencyKey, CloseShiftRequest request) {
        var shift = handlers.close(shiftId, SalesWebMapper.instant(request.getClosedAt()));
        return ResponseEntity.ok().eTag(Etags.of(shift.version())).body(mapper.shift(shift));
    }
}
