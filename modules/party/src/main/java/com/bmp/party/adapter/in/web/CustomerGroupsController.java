package com.bmp.party.adapter.in.web;

import com.bmp.party.adapter.in.web.generated.api.CustomerGroupsApi;
import com.bmp.party.adapter.in.web.generated.model.CreateCustomerGroupRequest;
import com.bmp.party.adapter.in.web.generated.model.CustomerGroup;
import com.bmp.party.adapter.in.web.generated.model.CustomerGroupPage;
import com.bmp.party.adapter.in.web.generated.model.UpdateCustomerGroupRequest;
import com.bmp.party.application.command.CustomerGroupHandlers;
import com.bmp.party.application.query.CustomerGroupView;
import com.bmp.party.application.query.PartyQueryService;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Customer groups. */
@RestController
class CustomerGroupsController implements CustomerGroupsApi {

    private final PartyQueryService queries;
    private final CustomerGroupHandlers handlers;
    private final PartyWebMapper mapper;

    CustomerGroupsController(PartyQueryService queries, CustomerGroupHandlers handlers, PartyWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:read')")
    public ResponseEntity<CustomerGroupPage> listCustomerGroups(@Nullable Integer limit, @Nullable String cursor) {
        PageResponse<CustomerGroupView> page = queries.groups(PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new CustomerGroupPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(PartiesController.page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'party:write')")
    public ResponseEntity<CustomerGroup> createCustomerGroup(UUID idempotencyKey, CreateCustomerGroupRequest request) {
        CustomerGroupView created = handlers.create(request.getCode(), request.getName());
        return ResponseEntity.created(URI.create("/api/v1/customer-groups/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:read')")
    public ResponseEntity<CustomerGroup> getCustomerGroup(UUID groupId) {
        CustomerGroupView view = queries.group(groupId);
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:write')")
    public ResponseEntity<CustomerGroup> updateCustomerGroup(
            UUID groupId, UpdateCustomerGroupRequest request, @Nullable String ifMatch) {
        CustomerGroupView view = handlers.update(
                groupId,
                IfMatch.expectedVersion(ifMatch),
                request.getName(),
                PartyRequests.status(request.getStatus()));
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }
}
