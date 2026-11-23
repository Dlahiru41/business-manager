package com.bmp.party.adapter.in.web;

import com.bmp.party.adapter.in.web.generated.api.PartiesApi;
import com.bmp.party.adapter.in.web.generated.model.CreatePartyRequest;
import com.bmp.party.adapter.in.web.generated.model.Party;
import com.bmp.party.adapter.in.web.generated.model.PartyPage;
import com.bmp.party.adapter.in.web.generated.model.PartyPagePage;
import com.bmp.party.adapter.in.web.generated.model.PartySearchResult;
import com.bmp.party.adapter.in.web.generated.model.PartyType;
import com.bmp.party.adapter.in.web.generated.model.Status;
import com.bmp.party.adapter.in.web.generated.model.UpdateCreditPolicyRequest;
import com.bmp.party.adapter.in.web.generated.model.UpdatePartyRequest;
import com.bmp.party.application.command.PartyHandlers;
import com.bmp.party.application.query.PartyQueryService;
import com.bmp.party.application.query.PartyView;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Customers and suppliers (PTY-01..PTY-04). */
@RestController
class PartiesController implements PartiesApi {

    private final PartyQueryService queries;
    private final PartyHandlers handlers;
    private final PartyWebMapper mapper;

    PartiesController(PartyQueryService queries, PartyHandlers handlers, PartyWebMapper mapper) {
        this.queries = queries;
        this.handlers = handlers;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:read')")
    public ResponseEntity<PartyPage> listParties(
            @Nullable PartyType type,
            @Nullable Status status,
            @Nullable UUID customerGroupId,
            @Nullable Integer limit,
            @Nullable String cursor) {
        PageResponse<PartyView> page = queries.parties(
                PartyRequests.type(type), PartyRequests.status(status), customerGroupId, PageQuery.of(limit, cursor));
        return ResponseEntity.ok(new PartyPage()
                .items(page.items().stream().map(mapper::toDto).toList())
                .page(page(page)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'party:create-customer')")
    public ResponseEntity<Party> createParty(UUID idempotencyKey, CreatePartyRequest request, @Nullable Boolean force) {
        PartyView created = handlers.create(new PartyHandlers.CreateParty(
                Objects.requireNonNull(PartyRequests.type(request.getType())),
                request.getDisplayName(),
                request.getPhone(),
                request.getEmail(),
                request.getTaxId(),
                PartyRequests.address(request.getAddress()),
                request.getCustomerGroupId(),
                Boolean.TRUE.equals(force)));
        return ResponseEntity.created(URI.create("/api/v1/parties/" + created.id()))
                .eTag(Etags.of(created.version()))
                .body(mapper.toDto(created));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:read')")
    public ResponseEntity<Party> getParty(UUID partyId) {
        return ok(queries.party(partyId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:write')")
    public ResponseEntity<Party> updateParty(
            UUID partyId, UpdatePartyRequest request, @Nullable Boolean force, @Nullable String ifMatch) {
        return ok(handlers.update(new PartyHandlers.UpdateParty(
                partyId,
                IfMatch.expectedVersion(ifMatch),
                PartyRequests.type(request.getType()),
                request.getDisplayName(),
                request.getPhone(),
                request.getEmail(),
                request.getTaxId(),
                PartyRequests.address(request.getAddress()),
                request.getCustomerGroupId(),
                PartyRequests.status(request.getStatus()),
                Boolean.TRUE.equals(force))));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:credit')")
    public ResponseEntity<Party> updateCreditPolicy(
            UUID partyId, UpdateCreditPolicyRequest request, @Nullable String ifMatch) {
        return ok(handlers.changeCreditPolicy(
                partyId, IfMatch.expectedVersion(ifMatch), PartyRequests.creditPolicy(request)));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'party:anonymise')")
    public ResponseEntity<Party> anonymiseParty(UUID partyId, UUID idempotencyKey) {
        return ok(handlers.anonymise(partyId));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'party:read')")
    public ResponseEntity<PartySearchResult> searchParties(
            String q, @Nullable PartyType type, @Nullable Integer limit) {
        int size = limit != null ? limit : PageQuery.DEFAULT_LIMIT;
        return ResponseEntity.ok(new PartySearchResult()
                .items(queries.search(q, PartyRequests.type(type), size).stream()
                        .map(mapper::toDto)
                        .toList()));
    }

    private ResponseEntity<Party> ok(PartyView view) {
        return ResponseEntity.ok().eTag(Etags.of(view.version())).body(mapper.toDto(view));
    }

    static PartyPagePage page(PageResponse<?> page) {
        return new PartyPagePage()
                .limit(page.page().limit())
                .nextCursor(page.page().nextCursor())
                .hasMore(page.page().hasMore());
    }
}
