package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.ExpenseCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Expense categories of the current tenant. */
public interface ExpenseCategoryRepository {

    Optional<ExpenseCategory> findById(UUID id);

    List<ExpenseCategory> page(@Nullable String afterCode, int limit);

    ExpenseCategory save(ExpenseCategory category);
}
