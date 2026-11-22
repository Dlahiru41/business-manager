package com.bmp.tenant;

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
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Every operation of contracts/openapi/tenant.yaml answers 401 and 403 as its x-permission says. */
@SpringBootTest(classes = TenantTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class TenantContractSecurityIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        TenantId tenant = new TenantApi(mvc).createTenant("Contract security " + UUID.randomUUID());
        String nobody = JwtTestTokens.builder().tenant(tenant).roles("NOBODY").build();

        ContractSecurity.assertEveryOperationIsProtected("tenant", mvc, context, nobody);
    }
}
