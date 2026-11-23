package com.bmp.procurement;

import static com.bmp.procurement.ProcurementApi.id;
import static com.bmp.procurement.ProcurementApi.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.procurement.ProcurementApiIT.Shop;
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
import tools.jackson.databind.JsonNode;

/** Tenant A's orders, receipts and invoices are invisible to tenant B; numbers are per tenant; RLS covers the schema. */
@SpringBootTest(classes = ProcurementTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class ProcurementIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void procurementSchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(List.of("procurement"), List.of());
    }

    @Test
    void anotherTenantSeesNoneOfTheDocumentsAndNumbersFromOne() {
        Shop a = new Shop(mvc, "Procurement isolation A");
        Shop b = new Shop(mvc, "Procurement isolation B");
        JsonNode order =
                json(a.api.post("/api/v1/purchase-orders", a.order(a.orderLine(a.small, "tray", "1", "1140"))));
        a.api.action("/api/v1/purchase-orders/" + id(order) + "/send");
        JsonNode receipt = a.eggDelivery();
        JsonNode invoice = json(a.api.post("/api/v1/supplier-invoices", """
                {"supplierId": "%s", "supplierRef": "A-1", "invoiceDate": "2026-11-23", "subtotal": "1.00",
                 "receiptIds": ["%s"]}
                """.formatted(a.supplier, id(receipt))));

        assertThatProblem(b.api.get("/api/v1/purchase-orders/" + id(order))).hasStatus(404);
        assertThatProblem(b.api.get("/api/v1/goods-receipts/" + id(receipt))).hasStatus(404);
        assertThatProblem(b.api.get("/api/v1/supplier-invoices/" + id(invoice))).hasStatus(404);
        assertThatProblem(b.api.action("/api/v1/goods-receipts/" + id(receipt) + "/cancel"))
                .hasStatus(404);
        assertThat(json(b.api.get("/api/v1/purchase-orders")).path("items")).isEmpty();
        assertThat(json(b.api.get("/api/v1/goods-receipts")).path("items")).isEmpty();
        assertThat(json(b.api.get("/api/v1/supplier-invoices")).path("items")).isEmpty();
        assertThatProblem(b.api.post(
                        "/api/v1/goods-receipts",
                        b.receipt(
                                ", \"purchaseOrderId\": \"" + id(order) + "\"",
                                Shop.receiptLine(b.small, "tray", "1", "1140", ""))))
                .as("A's order is unknown to B")
                .hasStatus(422)
                .hasCode("PURCHASE_ORDER_MISMATCH");
        assertThatProblem(b.api.post("/api/v1/supplier-invoices", """
                        {"supplierId": "%s", "supplierRef": "B-1", "invoiceDate": "2026-11-23", "subtotal": "1.00",
                         "receiptIds": ["%s"]}
                        """.formatted(b.supplier, id(receipt))))
                .hasStatus(422)
                .hasCode("UNKNOWN_RECEIPT");
        assertThatProblem(b.api.post("/api/v1/purchase-orders", """
                        {"supplierId": "%s", "locationId": "%s", "lines": [%s]}
                        """.formatted(
                                a.supplier, b.api.location, b.orderLine(b.small, "tray", "1", "1140"))))
                .as("A's supplier is unknown to B")
                .hasStatus(422)
                .hasCode("UNKNOWN_SUPPLIER");
        assertThat(json(b.api.post("/api/v1/purchase-orders", b.order(b.orderLine(b.small, "tray", "1", "1140"))))
                        .path("number")
                        .asString())
                .isEqualTo("PO-000001");
        Long visible = Objects.requireNonNull(TenantContext.callWith(
                b.api.tenant,
                () -> new TransactionTemplate(transactions)
                        .execute(s -> jdbc.sql("SELECT count(*) FROM procurement.goods_receipt WHERE tenant_id = :a")
                                .param("a", a.api.tenant.value())
                                .query(Long.class)
                                .single())));
        assertThat(visible).isZero();
    }
}
