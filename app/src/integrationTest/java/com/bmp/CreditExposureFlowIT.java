package com.bmp;

import static com.bmp.test.fixture.PilotShop.json;
import static com.bmp.test.fixture.PilotShop.unit;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Rule FIN-10 through the whole platform: sales asks finance for a customer's exposure, so a collection on account
 * raises the credit the customer may still take. Sales' own fallback ignores collections and would refuse the last bill.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithPostgres
@Import(TestJwtConfiguration.class)
class CreditExposureFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private PilotShop shop;
    private ApiClient till;
    private String cashier;
    private String register;
    private String customer;
    private int bills;

    @BeforeEach
    void setUp() {
        shop = PilotShopFixture.create(mvc, "Credit exposure " + UUID.randomUUID());
        String location = json(shop.get("/api/v1/locations"))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        String eggs = shop.variant("EGG-RED-L").path("id").asString();
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(
                                count("SELECT count(*) FROM sales.sale_item WHERE variant_id = '" + eggs + "'::uuid"))
                        .isEqualTo(1L));
        MvcTestResult settings = shop.get("/api/v1/settings/sales");
        assertThat(shop.patch(
                        "/api/v1/settings/sales",
                        "{\"values\": {\"allowCreditSales\": true}}",
                        PilotShop.etag(settings)))
                .hasStatusOk();
        register = json(shop.post(
                        "/api/v1/registers",
                        "{\"code\": \"R1\", \"name\": \"Counter\", \"locationId\": \"" + location
                                + "\", \"billPrefix\": \"R1\"}"))
                .path("id")
                .asString();
        // A cashier sells: the owner holds sales:credit-override and may go past any limit (ADR-025 §2).
        till = new ApiClient(mvc);
        cashier = JwtTestTokens.forRole(shop.tenant(), SystemRole.CASHIER);
        assertThat(till.post("/api/v1/shifts", cashier, "{\"registerId\": \"" + register + "\"}"))
                .hasStatus(201);
        MvcTestResult party = shop.post(
                "/api/v1/parties",
                "{\"type\": \"CUSTOMER\", \"displayName\": \"Hotel Lake View\", \"phone\": \"077 123 4567\"}");
        customer = json(party).path("id").asString();
        assertThat(shop.put(
                        "/api/v1/parties/" + customer + "/credit-policy",
                        "{\"creditAllowed\": true, \"creditLimit\": \"1000.00\", \"paymentTermsDays\": 30}",
                        PilotShop.etag(party)))
                .hasStatusOk();
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(count("SELECT count(*) FROM finance.party_ref WHERE party_id = '"
                                        + customer + "'::uuid")
                                + count("SELECT count(*) FROM sales.customer_ref WHERE credit_allowed"))
                        .isEqualTo(2L));
    }

    @Test
    void fin10ACollectionRaisesTheCreditACustomerMayStillTake() {
        assertThat(creditSale("10")).hasStatusOk();
        awaitOwed("550.00");

        assertThatProblem(creditSale("10"))
                .as("550 owed and 550 more is above the limit of 1000")
                .hasStatus(409)
                .hasCode("CREDIT_LIMIT_EXCEEDED");

        assertThat(shop.post(
                        "/api/v1/payments",
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"customerId\": \"" + customer
                                + "\", \"method\": \"CARD\", \"amount\": \"300.00\", \"reference\": \"AUTH-7731\"}"))
                .hasStatus(201);
        awaitOwed("250.00");

        assertThat(creditSale("10"))
                .as("250 owed and 550 more is within the limit")
                .hasStatusOk();
        awaitOwed("800.00");
        assertThat(amount("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM finance.journal_line l
                          JOIN finance.account a ON a.tenant_id = l.tenant_id AND a.id = l.account_id
                         WHERE a.system_key = 'AR'
                        """)).as("AR = Σ open customer items").isEqualByComparingTo("800.00");
    }

    private void awaitOwed(String amount) {
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(
                                amount("SELECT coalesce(sum(balance), 0) FROM finance.party_balance WHERE party_id = '"
                                        + customer + "'::uuid"))
                        .isEqualByComparingTo(amount));
    }

    /** Ten large red eggs at the retail price (55.00 each), all on credit. */
    private MvcTestResult creditSale(String qty) {
        bills++;
        JsonNode eggs = shop.variant("EGG-RED-L");
        MvcTestResult draft = till.post("/api/v1/bills", cashier, """
                {"id": "%s", "number": "R1-%06d", "registerId": "%s", "channel": "RETAIL", "customerId": "%s",
                 "lines": [{"id": "%s", "variantId": "%s", "packagingUnitId": "%s", "qty": "%s"}]}""".formatted(
                        UUID.randomUUID(),
                        bills,
                        register,
                        customer,
                        UUID.randomUUID(),
                        eggs.path("id").asString(),
                        unit(eggs, "piece"),
                        qty));
        assertThat(draft).hasStatus(201);
        JsonNode totals = json(draft).path("totals");
        String grand = totals.path("grandTotal").path("amount").asString();
        return till.post("/api/v1/bills/" + json(draft).path("id").asString() + "/complete", cashier, """
                {"tenders": [{"id": "%s", "method": "CREDIT", "amount": "%s"}], "totals": {"subtotal": "%s", "discountTotal": "%s",
                 "taxTotal": "%s", "grandTotal": "%s"}}""".formatted(
                        UUID.randomUUID(),
                        grand,
                        totals.path("subtotal").path("amount").asString(),
                        totals.path("discountTotal").path("amount").asString(),
                        totals.path("taxTotal").path("amount").asString(),
                        grand));
    }

    private long count(String query) {
        return Objects.requireNonNull(TenantContext.callWith(
                shop.tenant(),
                () -> new TransactionTemplate(transactions)
                        .execute(s -> jdbc.sql(query).query(Long.class).single())));
    }

    private BigDecimal amount(String query) {
        return Objects.requireNonNull(TenantContext.callWith(
                shop.tenant(),
                () -> new TransactionTemplate(transactions)
                        .execute(s -> jdbc.sql(query).query(BigDecimal.class).single())));
    }
}
