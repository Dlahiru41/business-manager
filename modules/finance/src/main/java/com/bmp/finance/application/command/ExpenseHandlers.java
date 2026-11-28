package com.bmp.finance.application.command;

import com.bmp.finance.application.Drawers;
import com.bmp.finance.application.FinanceEvents;
import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.application.OpenItems;
import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.AccountStatus;
import com.bmp.finance.domain.model.AccountType;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.Expense;
import com.bmp.finance.domain.model.ExpenseCategory;
import com.bmp.finance.domain.model.PaidFrom;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.port.AccountRepository;
import com.bmp.finance.domain.port.DocumentLedger;
import com.bmp.finance.domain.port.ExpenseCategoryRepository;
import com.bmp.finance.domain.port.PartyDirectory;
import com.bmp.finance.domain.posting.ExpenseRule;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.VersionConflict;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rule FIN-09: expense categories, and expenses paid from the drawer, the bank or on account. */
@Service
public class ExpenseHandlers {

    private static final Logger log = LoggerFactory.getLogger(ExpenseHandlers.class);
    private static final ExpenseRule RULE = new ExpenseRule();

    private final ExpenseCategoryRepository categories;
    private final AccountRepository accounts;
    private final DocumentLedger documents;
    private final JournalPoster poster;
    private final OpenItems openItems;
    private final PartyDirectory parties;
    private final Drawers drawers;
    private final FinanceEvents events;
    private final FinanceSettings settings;
    private final Clock clock;

    public ExpenseHandlers(
            ExpenseCategoryRepository categories,
            AccountRepository accounts,
            DocumentLedger documents,
            JournalPoster poster,
            OpenItems openItems,
            PartyDirectory parties,
            Drawers drawers,
            FinanceEvents events,
            FinanceSettings settings,
            Clock clock) {
        this.categories = categories;
        this.accounts = accounts;
        this.documents = documents;
        this.poster = poster;
        this.openItems = openItems;
        this.parties = parties;
        this.drawers = drawers;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    public record CreateCategory(UUID id, String code, String name, UUID accountId) {}

    public record UpdateCategory(
            UUID id,
            long expectedVersion,
            @Nullable String name,
            @Nullable UUID accountId,
            @Nullable AccountStatus status) {}

    public record RecordExpense(
            UUID id,
            UUID categoryId,
            LocalDate expenseDate,
            BigDecimal amount,
            PaidFrom paidFrom,
            @Nullable UUID drawerSessionId,
            @Nullable UUID supplierId,
            @Nullable LocalDate dueDate,
            String description,
            List<String> attachmentKeys) {

        public RecordExpense {
            attachmentKeys = List.copyOf(attachmentKeys);
        }
    }

    @Transactional
    public ExpenseCategory createCategory(CreateCategory c) {
        expenseAccount(c.accountId());
        return categories.save(
                ExpenseCategory.create(c.id(), TenantContext.require(), c.code(), c.name(), c.accountId()));
    }

    @Transactional
    public ExpenseCategory updateCategory(UpdateCategory u) {
        ExpenseCategory category = categories
                .findById(u.id())
                .orElseThrow(() -> new NotFoundException(
                        FinanceErrorCode.EXPENSE_CATEGORY_NOT_FOUND, Map.of("categoryId", u.id())));
        if (category.version() != u.expectedVersion()) {
            throw new VersionConflict(category.version());
        }
        if (u.name() != null) {
            category.rename(u.name());
        }
        if (u.accountId() != null) {
            expenseAccount(u.accountId());
            category.postTo(u.accountId());
        }
        if (u.status() != null) {
            category.changeStatus(u.status());
        }
        return categories.save(category);
    }

    /**
     * Rule FIN-09: the expense posts Dr the category's account; from the drawer it is a PAID_OUT of an open session,
     * on account it opens a payable due on the given date or after the supplier's terms.
     */
    @Transactional
    public Expense record(RecordExpense r) {
        Optional<Expense> known = documents.expense(r.id());
        if (known.isPresent()) {
            return known.get();
        }
        ExpenseCategory category = categories
                .findById(r.categoryId())
                .filter(ExpenseCategory::isActive)
                .orElseThrow(() -> new BusinessRuleViolation(
                        FinanceErrorCode.UNKNOWN_EXPENSE_CATEGORY, Map.of("categoryId", r.categoryId())));
        UUID supplier = null;
        LocalDate due = null;
        switch (r.paidFrom()) {
            case CASH_DRAWER ->
                drawers.move(
                        r.drawerSessionId(),
                        r.id(),
                        "PAID_OUT",
                        r.amount(),
                        "Expense " + category.code(),
                        clock.instant());
            case PAYABLE -> {
                supplier = requiredSupplier(r.supplierId());
                due = r.dueDate() != null ? r.dueDate() : r.expenseDate().plusDays(terms(supplier));
            }
            case BANK -> {
                // Nothing moves through a drawer.
            }
        }
        UUID supplierId = supplier;
        PostedJournal journal = poster.post(() -> RULE.post(new ExpenseRule.Expense(
                        r.id(),
                        category.accountId(),
                        r.amount(),
                        r.paidFrom(),
                        supplierId,
                        r.expenseDate(),
                        r.description())))
                .orElseThrow();
        Expense expense = new Expense(
                r.id(),
                category.id(),
                r.expenseDate(),
                r.amount(),
                settings.currency(),
                r.paidFrom(),
                r.paidFrom() == PaidFrom.CASH_DRAWER ? r.drawerSessionId() : null,
                supplierId,
                due,
                r.description(),
                r.attachmentKeys(),
                journal.id(),
                clock.instant());
        documents.append(expense);
        if (supplierId != null && due != null) {
            openItems.open(
                    PartyRef.supplier(supplierId),
                    DocumentType.EXPENSE,
                    r.id(),
                    r.amount(),
                    r.expenseDate(),
                    due,
                    List.of());
        }
        events.expenseRecorded(expense, category.accountId());
        log.atInfo()
                .addKeyValue("expenseId", expense.id())
                .addKeyValue("category", category.code())
                .addKeyValue("paidFrom", expense.paidFrom())
                .log("Expense recorded");
        return expense;
    }

    private UUID requiredSupplier(@Nullable UUID supplierId) {
        if (supplierId == null) {
            throw new BusinessRuleViolation(FinanceErrorCode.SUPPLIER_REQUIRED, Map.of("field", "supplierId"));
        }
        return supplierId;
    }

    private int terms(UUID supplier) {
        return parties.find(supplier)
                .filter(p -> p.type().equals("SUPPLIER") || p.type().equals("BOTH"))
                .orElseThrow(() ->
                        new BusinessRuleViolation(FinanceErrorCode.UNKNOWN_SUPPLIER, Map.of("supplierId", supplier)))
                .paymentTermsDays();
    }

    private void expenseAccount(UUID accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() ->
                        new BusinessRuleViolation(FinanceErrorCode.UNKNOWN_ACCOUNT, Map.of("accountId", accountId)));
        if (account.type() != AccountType.EXPENSE) {
            throw new BusinessRuleViolation(FinanceErrorCode.ACCOUNT_NOT_EXPENSE, Map.of("accountId", accountId));
        }
    }
}
