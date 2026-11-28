package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.api.AccountsApi;
import com.bmp.finance.adapter.in.web.generated.model.Account;
import com.bmp.finance.adapter.in.web.generated.model.AccountStatus;
import com.bmp.finance.adapter.in.web.generated.model.AccountType;
import com.bmp.finance.adapter.in.web.generated.model.CreateAccountRequest;
import com.bmp.finance.adapter.in.web.generated.model.ListAccounts200Response;
import com.bmp.finance.adapter.in.web.generated.model.UpdateAccountRequest;
import com.bmp.finance.application.command.AccountHandlers;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountsController implements AccountsApi {

    private final AccountHandlers handlers;
    private final FinanceQueries queries;
    private final FinanceWebMapper mapper;

    AccountsController(AccountHandlers handlers, FinanceQueries queries, FinanceWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ListAccounts200Response> listAccounts(
            @Nullable AccountType type, @Nullable AccountStatus status, Integer limit, @Nullable String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        var page = PageResponse.of(
                queries.accounts(
                        type == null ? null : com.bmp.finance.domain.model.AccountType.valueOf(type.name()),
                        status == null ? null : com.bmp.finance.domain.model.AccountStatus.valueOf(status.name()),
                        Cursors.first(query.cursor()),
                        query.fetchSize()),
                query,
                a -> List.of(a.code()));
        return ResponseEntity.ok(new ListAccounts200Response()
                .items(page.items().stream().map(mapper::account).toList())
                .page(FinanceWebMapper.page(page.page())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:write')")
    public ResponseEntity<Account> createAccount(UUID idempotencyKey, CreateAccountRequest r) {
        var account = handlers.create(new AccountHandlers.CreateAccount(
                r.getId(),
                r.getCode(),
                r.getName(),
                com.bmp.finance.domain.model.AccountType.valueOf(r.getType().name()),
                r.getSubtype(),
                r.getParentId()));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.id()))
                .eTag(Etags.of(account.version()))
                .body(mapper.account(account));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<Account> getAccount(UUID accountId) {
        var account = handlers.find(accountId);
        return ResponseEntity.ok().eTag(Etags.of(account.version())).body(mapper.account(account));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:write')")
    public ResponseEntity<Account> updateAccount(UUID accountId, UpdateAccountRequest r, @Nullable String ifMatch) {
        var account = handlers.update(new AccountHandlers.UpdateAccount(
                accountId,
                IfMatch.expectedVersion(ifMatch),
                r.getCode(),
                r.getName(),
                r.getType() == null
                        ? null
                        : com.bmp.finance.domain.model.AccountType.valueOf(
                                r.getType().name()),
                r.getSubtype(),
                r.getStatus() == null
                        ? null
                        : com.bmp.finance.domain.model.AccountStatus.valueOf(
                                r.getStatus().name())));
        return ResponseEntity.ok().eTag(Etags.of(account.version())).body(mapper.account(account));
    }
}
