package com.bmp.finance;

import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;

/** Every finance operation is protected by the permission its contract names (08 §3). */
@SpringBootTest(classes = FinanceTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class FinanceContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        FinanceShop shop = new FinanceShop(mvc, jdbc, transactions, publisher, "Finance security");
        String nobody =
                JwtTestTokens.builder().tenant(shop.tenant).roles("NOBODY").build();
        ContractSecurity.assertEveryOperationIsProtected("finance", mvc, context, nobody);
    }
}
