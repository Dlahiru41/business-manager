package com.bmp.party;

import static com.bmp.party.PartyApi.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.context.TenantContext;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant A's parties are invisible to tenant B through the API and SQL; RLS covers the schema (06 par. 10). */
@SpringBootTest(classes = PartyTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class PartyIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void partySchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(List.of("party"), List.of());
    }

    @Test
    void anotherTenantSeesNoneOfTheParties() {
        PartyApi a = new PartyApi(mvc, "Party isolation A");
        PartyApi b = new PartyApi(mvc, "Party isolation B");
        String party = a.party(PartyApiIT.SUPPLIER).path("id").asString();
        String group = json(a.post("/api/v1/customer-groups", "{\"code\": \"G\", \"name\": \"G\"}"))
                .path("id")
                .asString();

        assertThatProblem(b.get("/api/v1/parties/" + party)).hasStatus(404).hasCode("PARTY_NOT_FOUND");
        assertThatProblem(b.get("/api/v1/customer-groups/" + group)).hasStatus(404);
        assertThat(json(b.get("/api/v1/parties")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/parties:search?q=kegalle")).path("items"))
                .isEmpty();
        assertThat(json(b.get("/api/v1/customer-groups")).path("items")).isEmpty();
        assertThat(b.post("/api/v1/parties", PartyApiIT.SUPPLIER))
                .as("the same phone is no duplicate in another tenant")
                .hasStatus(201);
        Long visible = Objects.requireNonNull(TenantContext.callWith(
                b.tenant,
                () -> new TransactionTemplate(transactions)
                        .execute(s -> jdbc.sql("SELECT count(*) FROM party.party WHERE tenant_id = :a")
                                .param("a", a.tenant.value())
                                .query(Long.class)
                                .single())));
        assertThat(visible).isZero();
    }
}
