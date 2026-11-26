package com.bmp.sales;

import static com.bmp.sales.SalesApi.cash;
import static com.bmp.sales.SalesApi.json;
import static com.bmp.sales.SalesApi.tender;
import static com.bmp.sales.SalesApi.totals;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.sales.SalesTestApplication.Approvers;
import com.bmp.sales.api.spi.CreditExposureProvider;
import com.bmp.sales.application.LinePricer.LineInput;
import com.bmp.sales.application.command.BillHandlers;
import com.bmp.sales.application.command.BillHandlers.SubmitBill;
import com.bmp.sales.application.command.BillHandlers.TenderInput;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.sales.domain.model.Totals;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.PartyId;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The SPIs sales serves (T03-05): the default credit exposure behind SAL-07, online and offline, and the business
 * activity probe behind TEN-02.
 */
@SpringBootTest(classes = SalesTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class SalesSpisIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    CreditExposureProvider exposure;

    @Autowired
    BillHandlers bills;

    @Autowired
    Approvers approvers;

    private BigDecimal exposureOf(SalesApi api, UUID customer) {
        return Objects.requireNonNull(TenantContext.callWith(
                api.tenant,
                () -> new TransactionTemplate(transactions).execute(s -> exposure.exposureOf(PartyId.of(customer)))));
    }

    private static String eggs(SalesApi api, String qty) {
        return api.line("EGG-RED-L", "piece", qty, "55.00", "");
    }

    @Test
    void sal07ExposureIsTheCustomersCreditSalesLessCreditRefundsAndIgnoresVoidedBills() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Exposure");
        api.settings("sales", "{\"allowCreditSales\": true}");
        UUID customer = api.creditCustomer("Hotel Lake View", "1000.00");
        String withCustomer = ", \"customerId\": \"" + customer + "\"";
        UUID manager = approvers.add("sales:void");
        assertThat(exposureOf(api, customer)).isEqualByComparingTo("0");

        JsonNode credit = json(api.submit(api.completed(
                UUID.randomUUID(),
                api.nextNumber(),
                withCustomer,
                List.of(eggs(api, "10")),
                List.of(tender("CREDIT", "550.00", null)),
                totals("550.00", "0.00", "0.00", "550.00"))));
        UUID mixed = UUID.randomUUID();
        api.submit(api.completed(
                mixed,
                api.nextNumber(),
                withCustomer,
                List.of(eggs(api, "4")),
                List.of(tender("CREDIT", "120.00", null), cash("100.00", "100.00")),
                totals("220.00", "0.00", "0.00", "220.00")));
        assertThat(exposureOf(api, customer)).isEqualByComparingTo("670.00");

        MvcTestResult returned = api.post(
                "/api/v1/bills/" + credit.path("id").asString() + "/returns",
                "{\"id\": \"" + UUID.randomUUID() + "\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \""
                        + credit.path("lines").get(0).path("id").asString()
                        + "\", \"qty\": \"2\", \"restock\": false}]}");
        assertThat(json(returned).path("refundTenders").get(0).path("method").asString())
                .as("a credit bill is refunded to the customer's account")
                .isEqualTo("CREDIT");
        assertThat(exposureOf(api, customer)).isEqualByComparingTo("560.00");
        assertThat(api.post(
                        "/api/v1/bills/" + mixed + "/void",
                        "{\"reason\": \"Wrong customer\", \"approvedBy\": \"" + manager + "\"}"))
                .hasStatusOk();
        assertThat(exposureOf(api, customer)).as("a voided bill owes nothing").isEqualByComparingTo("440.00");
        assertThat(exposureOf(api, UUID.randomUUID())).isEqualByComparingTo("0");
    }

    @Test
    void sal07OfflineACreditSaleAboveTheLimitIsAcceptedAndFlagged() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Offline credit");
        api.settings("sales", "{\"allowCreditSales\": true}");
        UUID customer = api.creditCustomer("Hotel Lake View", "500.00");
        BigDecimal total = new BigDecimal("550.00");
        SubmitBill bill = new SubmitBill(
                UUID.randomUUID(),
                api.nextNumber(),
                api.register,
                api.shift,
                SalesChannel.RETAIL,
                customer,
                List.of(new LineInput(
                        UUID.randomUUID(),
                        UUID.fromString(api.variant("EGG-RED-L").path("id").asString()),
                        UUID.fromString(api.unit("EGG-RED-L", "piece")),
                        BigDecimal.TEN,
                        false,
                        new BigDecimal("55.00"),
                        null,
                        null,
                        null,
                        null)),
                null,
                true,
                List.of(new TenderInput(UUID.randomUUID(), TenderMethod.CREDIT, total, null, null)),
                new Totals(total, BigDecimal.ZERO, BigDecimal.ZERO, total),
                Instant.parse("2026-11-25T09:00:00Z"),
                null,
                null,
                null);

        Bill accepted = Objects.requireNonNull(
                        TenantContext.callWith(api.tenant, () -> bills.submit(bill, Mode.OFFLINE)))
                .bill();

        assertThat(accepted.flags()).containsExactly(BillFlag.CREDIT_LIMIT_EXCEEDED);
        assertThat(exposureOf(api, customer)).isEqualByComparingTo("550.00");
        assertThatProblem(api.post(
                        "/api/v1/bills",
                        api.cashier,
                        api.completed(
                                UUID.randomUUID(),
                                api.nextNumber(),
                                ", \"customerId\": \"" + customer + "\"",
                                List.of(eggs(api, "1")),
                                List.of(tender("CREDIT", "55.00", null)),
                                totals("55.00", "0.00", "0.00", "55.00"))))
                .as("online, the customer is already above the limit and a cashier may not override")
                .hasStatus(409)
                .hasCode("CREDIT_LIMIT_EXCEEDED");
    }

    @Test
    void ten02TheFirstBillLocksTheTenantsCurrencyAndTimeZone() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Regional lock");
        assertThat(patchGeneral(api, "{\"timeZone\": \"Asia/Kolkata\"}"))
                .as("a new tenant without bills may still change it")
                .hasStatusOk();

        api.submit(api.completed(
                UUID.randomUUID(),
                api.nextNumber(),
                "",
                List.of(eggs(api, "2")),
                List.of(cash("110.00", "110.00")),
                totals("110.00", "0.00", "0.00", "110.00")));

        assertThatProblem(patchGeneral(api, "{\"timeZone\": \"Asia/Colombo\"}"))
                .hasStatus(409)
                .hasCode("SETTING_LOCKED");
        assertThatProblem(patchGeneral(api, "{\"currency\": \"USD\"}"))
                .hasStatus(409)
                .hasCode("SETTING_LOCKED");
    }

    private static MvcTestResult patchGeneral(SalesApi api, String values) {
        MvcTestResult current = api.client.get("/api/v1/settings/general", api.owner);
        return api.client.patch(
                "/api/v1/settings/general", api.owner, "{\"values\": " + values + "}", ApiClient.etag(current));
    }
}
