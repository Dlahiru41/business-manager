package com.bmp.sales.adapter.in.web;

import com.bmp.sales.adapter.in.web.generated.api.RegistersApi;
import com.bmp.sales.adapter.in.web.generated.model.CreateRegisterRequest;
import com.bmp.sales.adapter.in.web.generated.model.Register;
import com.bmp.sales.adapter.in.web.generated.model.RegisterPage;
import com.bmp.sales.adapter.in.web.generated.model.UpdateRegisterRequest;
import com.bmp.sales.application.command.RegisterHandlers;
import com.bmp.sales.application.query.SalesQueryService;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Registers (SAL-01, SAL-12). */
@RestController
class RegistersController implements RegistersApi {

    private final RegisterHandlers handlers;
    private final SalesQueryService queries;
    private final SalesWebMapper mapper;

    RegistersController(RegisterHandlers handlers, SalesQueryService queries, SalesWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:admin')")
    public ResponseEntity<RegisterPage> listRegisters(
            @Nullable UUID locationId, Integer limit, @Nullable String cursor) {
        var page = queries.registers(locationId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new RegisterPage()
                .items(page.items().stream().map(mapper::register).toList())
                .page(mapper.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'sales:admin')")
    public ResponseEntity<Register> createRegister(UUID idempotencyKey, CreateRegisterRequest request) {
        var register = handlers.create(
                request.getCode(),
                request.getName(),
                request.getLocationId(),
                request.getDeviceId(),
                request.getBillPrefix());
        return ResponseEntity.created(URI.create("/api/v1/registers/" + register.id()))
                .eTag(Etags.of(register.version()))
                .body(mapper.register(register));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:admin')")
    public ResponseEntity<Register> getRegister(UUID registerId) {
        var register = queries.register(registerId);
        return ResponseEntity.ok().eTag(Etags.of(register.version())).body(mapper.register(register));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'sales:admin')")
    public ResponseEntity<Register> updateRegister(
            UUID registerId, UpdateRegisterRequest request, @Nullable String ifMatch) {
        var register = handlers.update(
                registerId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                request.getDeviceId(),
                request.getBillPrefix(),
                request.getStatus() == null ? null : request.getStatus().getValue());
        return ResponseEntity.ok().eTag(Etags.of(register.version())).body(mapper.register(register));
    }
}
