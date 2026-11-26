package com.bmp.sales;

import static com.bmp.sales.SalesApi.cash;
import static com.bmp.sales.SalesApi.json;
import static com.bmp.sales.SalesApi.totals;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * Tenant A's registers, shifts, bills and credit notes are invisible to tenant B; bill and credit-note numbers are per
 * tenant; RLS covers the schema and credit notes are append-only.
 */
@SpringBootTest(classes = SalesTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class SalesIsolationIT {

    private static final String RETURN =
            "{\"id\": \"%s\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \"%s\", "
                    + "\"qty\": \"1\", \"restock\": true}]}";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void salesSchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(
                List.of("sales"), List.of("sales.credit_note", "sales.credit_note_line", "sales.credit_note_refund"));
    }

    @Test
    void anotherTenantSeesNoneOfTheTillsBillsAndCreditNotesOfOne() {
        SalesApi a = new SalesApi(mvc, jdbc, transactions, "Sales isolation A");
        SalesApi b = new SalesApi(mvc, jdbc, transactions, "Sales isolation B");
        UUID bill = UUID.randomUUID();
        String number = a.nextNumber();
        JsonNode sold = json(a.submit(twoEggs(a, bill, number)));
        String line = sold.path("lines").get(0).path("id").asString();
        UUID note = UUID.randomUUID();
        assertThat(a.post("/api/v1/bills/" + bill + "/returns", RETURN.formatted(note, line)))
                .hasStatus(201);

        assertThatProblem(b.get("/api/v1/registers/" + a.register))
                .hasStatus(404)
                .hasCode("REGISTER_NOT_FOUND");
        assertThatProblem(b.get("/api/v1/bills/" + bill)).hasStatus(404).hasCode("BILL_NOT_FOUND");
        assertThatProblem(b.get("/api/v1/credit-notes/" + note)).hasStatus(404).hasCode("CREDIT_NOTE_NOT_FOUND");
        assertThatProblem(b.post("/api/v1/bills/" + bill + "/returns", RETURN.formatted(UUID.randomUUID(), line)))
                .hasStatus(404)
                .hasCode("BILL_NOT_FOUND");
        assertThatProblem(b.post("/api/v1/shifts/" + a.shift + "/close", "{}"))
                .hasStatus(404)
                .hasCode("SHIFT_NOT_FOUND");
        assertThat(json(b.get("/api/v1/registers")).path("items"))
                .as("only B's own register")
                .hasSize(1);
        assertThat(json(b.get("/api/v1/bills")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/credit-notes")).path("items")).isEmpty();
        assertThatProblem(b.submit(twoEggs(b, UUID.randomUUID(), b.nextNumber())
                        .replace(b.register.toString(), a.register.toString())))
                .as("A's register is unknown to B")
                .hasStatus(422)
                .hasCode("UNKNOWN_REGISTER");

        UUID own = UUID.randomUUID();
        JsonNode first = json(b.submit(twoEggs(b, own, number)));
        assertThat(first.path("number").asString())
                .as("bill numbers are per tenant")
                .isEqualTo(number);
        String ownLine = first.path("lines").get(0).path("id").asString();
        assertThat(json(b.post("/api/v1/bills/" + own + "/returns", RETURN.formatted(UUID.randomUUID(), ownLine)))
                        .path("number")
                        .asString())
                .as("credit-note numbers are per tenant")
                .isEqualTo("CN-000001");
        assertThat(b.<Long>sql(j -> j.sql("SELECT count(*) FROM sales.bill WHERE tenant_id = :a")
                        .param("a", a.tenant.value())
                        .query(Long.class)
                        .single()))
                .isZero();
    }

    private static String twoEggs(SalesApi api, UUID id, String number) {
        return api.completed(
                id,
                number,
                "",
                List.of(api.line("EGG-RED-L", "piece", "2", "55.00", "")),
                List.of(cash("110.00", "110.00")),
                totals("110.00", "0.00", "0.00", "110.00"));
    }
}
