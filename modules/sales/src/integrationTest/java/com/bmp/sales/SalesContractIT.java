package com.bmp.sales;

import static com.bmp.sales.SalesApi.cash;
import static com.bmp.sales.SalesApi.json;
import static com.bmp.sales.SalesApi.totals;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.sales.SalesTestApplication.Approvers;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleVoided;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Sales keeps its contracts: every operation protected as x-permission says; its events as AsyncAPI says. */
@SpringBootTest(classes = SalesTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class SalesContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    @Autowired
    Approvers approvers;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Sales security");
        String nobody =
                JwtTestTokens.builder().tenant(api.tenant).roles("NOBODY").build();
        ContractSecurity.assertEveryOperationIsProtected("sales", mvc, context, nobody);
    }

    @Test
    void salesEventsConformToTheAsyncApiContract() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Sales events");
        UUID manager = approvers.add("sales:void");
        UUID sold = UUID.randomUUID();
        String line = json(api.submit(api.completed(
                        sold,
                        api.nextNumber(),
                        "",
                        List.of(
                                api.line("EGG-RED-L", "piece", "10", "55.00", ", \"lineDiscount\": \"5.00\""),
                                api.line("OIL", "bottle-750", "1", "550.00", "")),
                        List.of(cash("1095.00", "1100.00")),
                        totals("1100.00", "5.00", "83.90", "1095.00"))))
                .path("lines")
                .get(0)
                .path("id")
                .asString();
        assertThat(api.post(
                        "/api/v1/bills/" + sold + "/returns",
                        "{\"id\": \"" + UUID.randomUUID()
                                + "\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \"" + line
                                + "\", \"qty\": \"2\", \"restock\": false}]}"))
                .hasStatus(201);
        UUID voided = UUID.randomUUID();
        api.submit(api.completed(
                voided,
                api.nextNumber(),
                "",
                List.of(api.line("EGG-RED-L", "piece", "2", "55.00", "")),
                List.of(cash("110.00", "110.00")),
                totals("110.00", "0.00", "0.00", "110.00")));
        assertThat(api.post(
                        "/api/v1/bills/" + voided + "/void",
                        "{\"reason\": \"Wrong customer\", \"approvedBy\": \"" + manager + "\"}"))
                .hasStatusOk();

        assertThat(events.stream(SaleCompleted.class)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(events.stream(SaleReturned.class)).isNotEmpty();
        assertThat(events.stream(SaleVoided.class)).isNotEmpty();
        events.stream(SaleCompleted.class)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "SaleCompleted"));
        events.stream(SaleReturned.class)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "SaleReturned"));
        events.stream(SaleVoided.class)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "SaleVoided"));
    }
}
