package com.bmp.finance.application.command;

import com.bmp.finance.domain.model.Account;
import com.bmp.finance.domain.model.ExpenseCategory;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.finance.domain.port.AccountRepository;
import com.bmp.finance.domain.port.ExpenseCategoryRepository;
import com.bmp.finance.domain.service.ChartOfAccounts;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.TenantId;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rule FIN-01: the default chart and its expense categories for a new tenant, once. */
@Service
public class SeedChartHandler {

    private static final Logger log = LoggerFactory.getLogger(SeedChartHandler.class);

    private final AccountRepository accounts;
    private final ExpenseCategoryRepository categories;
    private final IdGenerator ids;

    public SeedChartHandler(AccountRepository accounts, ExpenseCategoryRepository categories, IdGenerator ids) {
        this.accounts = accounts;
        this.categories = categories;
        this.ids = ids;
    }

    @Transactional
    public void seed() {
        if (accounts.hasSystemAccounts()) {
            return;
        }
        TenantId tenant = TenantContext.require();
        Map<SystemKey, Account> seeded = new EnumMap<>(SystemKey.class);
        for (SystemKey key : ChartOfAccounts.accounts()) {
            seeded.put(key, accounts.save(Account.seeded(ids.newUuid(), tenant, key)));
        }
        for (ChartOfAccounts.DefaultCategory category : ChartOfAccounts.expenseCategories()) {
            categories.save(ExpenseCategory.create(
                    ids.newUuid(),
                    tenant,
                    category.code(),
                    category.name(),
                    java.util.Objects.requireNonNull(seeded.get(category.account()))
                            .id()));
        }
        log.atInfo().addKeyValue("accounts", seeded.size()).log("Chart of accounts seeded");
    }
}
