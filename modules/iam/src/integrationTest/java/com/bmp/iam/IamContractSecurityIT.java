package com.bmp.iam;

import com.bmp.shared.id.TenantId;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;

/** Every operation of contracts/openapi/iam.yaml answers 401 and 403 as its x-permission says. */
@SpringBootTest(classes = IamTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class IamContractSecurityIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        TenantId tenant =
                new IamApi(mvc, jdbc, transactionManager).createTenant("Contract security " + UUID.randomUUID());
        String nobody = JwtTestTokens.builder().tenant(tenant).roles("NOBODY").build();

        ContractSecurity.assertEveryOperationIsProtected("iam", mvc, context, nobody);
    }
}
