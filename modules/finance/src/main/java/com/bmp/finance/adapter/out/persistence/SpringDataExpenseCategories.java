package com.bmp.finance.adapter.out.persistence;

import com.bmp.finance.domain.model.ExpenseCategory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataExpenseCategories extends JpaRepository<ExpenseCategory, UUID> {}
