package com.bmp.finance.adapter.in.web;

import com.bmp.finance.adapter.in.web.generated.api.ExpensesApi;
import com.bmp.finance.adapter.in.web.generated.model.CreateExpenseCategoryRequest;
import com.bmp.finance.adapter.in.web.generated.model.Expense;
import com.bmp.finance.adapter.in.web.generated.model.ExpenseCategory;
import com.bmp.finance.adapter.in.web.generated.model.ListExpenseCategories200Response;
import com.bmp.finance.adapter.in.web.generated.model.ListExpenses200Response;
import com.bmp.finance.adapter.in.web.generated.model.PaidFrom;
import com.bmp.finance.adapter.in.web.generated.model.RecordExpenseRequest;
import com.bmp.finance.adapter.in.web.generated.model.UpdateExpenseCategoryRequest;
import com.bmp.finance.application.command.ExpenseHandlers;
import com.bmp.finance.application.query.FinanceQueries;
import com.bmp.finance.domain.model.AccountStatus;
import com.bmp.web.etag.Etags;
import com.bmp.web.etag.IfMatch;
import com.bmp.web.idempotency.Idempotent;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExpensesController implements ExpensesApi {

    private final ExpenseHandlers handlers;
    private final FinanceQueries queries;
    private final FinanceWebMapper mapper;

    ExpensesController(ExpenseHandlers handlers, FinanceQueries queries, FinanceWebMapper mapper) {
        this.handlers = handlers;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ListExpenseCategories200Response> listExpenseCategories(
            Integer limit, @Nullable String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        var page = PageResponse.of(
                queries.categories(Cursors.first(query.cursor()), query.fetchSize()), query, c -> List.of(c.code()));
        return ResponseEntity.ok(new ListExpenseCategories200Response()
                .items(page.items().stream().map(mapper::category).toList())
                .page(FinanceWebMapper.page(page.page())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:write')")
    public ResponseEntity<ExpenseCategory> createExpenseCategory(UUID idempotencyKey, CreateExpenseCategoryRequest r) {
        var category = handlers.createCategory(
                new ExpenseHandlers.CreateCategory(r.getId(), r.getCode(), r.getName(), r.getAccountId()));
        return ResponseEntity.created(URI.create("/api/v1/expense-categories/" + category.id()))
                .eTag(Etags.of(category.version()))
                .body(mapper.category(category));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:write')")
    public ResponseEntity<ExpenseCategory> updateExpenseCategory(
            UUID categoryId, UpdateExpenseCategoryRequest r, @Nullable String ifMatch) {
        var category = handlers.updateCategory(new ExpenseHandlers.UpdateCategory(
                categoryId,
                IfMatch.expectedVersion(ifMatch),
                r.getName(),
                r.getAccountId(),
                r.getStatus() == null
                        ? null
                        : AccountStatus.valueOf(r.getStatus().name())));
        return ResponseEntity.ok().eTag(Etags.of(category.version())).body(mapper.category(category));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<ListExpenses200Response> listExpenses(
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable UUID categoryId,
            @Nullable PaidFrom paidFrom,
            Integer limit,
            @Nullable String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        var page = PageResponse.of(
                queries.expenses(
                        from,
                        to,
                        categoryId,
                        paidFrom == null ? null : com.bmp.finance.domain.model.PaidFrom.valueOf(paidFrom.name()),
                        Cursors.dated(query.cursor()),
                        query.fetchSize()),
                query,
                e -> List.of(e.expenseDate().toString(), e.id().toString()));
        return ResponseEntity.ok(new ListExpenses200Response()
                .items(page.items().stream().map(mapper::expense).toList())
                .page(FinanceWebMapper.page(page.page())));
    }

    @Override
    @Idempotent
    @PreAuthorize("hasPermission(null, 'finance:expense')")
    public ResponseEntity<Expense> recordExpense(UUID idempotencyKey, RecordExpenseRequest r) {
        var expense = handlers.record(new ExpenseHandlers.RecordExpense(
                r.getId(),
                r.getCategoryId(),
                r.getExpenseDate(),
                new BigDecimal(r.getAmount()),
                com.bmp.finance.domain.model.PaidFrom.valueOf(r.getPaidFrom().name()),
                r.getDrawerSessionId(),
                r.getSupplierId(),
                r.getDueDate(),
                r.getDescription(),
                r.getAttachmentKeys() == null ? List.of() : r.getAttachmentKeys()));
        return ResponseEntity.created(URI.create("/api/v1/expenses/" + expense.id()))
                .body(mapper.expense(expense));
    }

    @Override
    @PreAuthorize("hasPermission(null, 'finance:read')")
    public ResponseEntity<Expense> getExpense(UUID expenseId) {
        return ResponseEntity.ok(mapper.expense(queries.expense(expenseId)));
    }
}
