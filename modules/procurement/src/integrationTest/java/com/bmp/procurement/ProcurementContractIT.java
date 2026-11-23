package com.bmp.procurement;

import static com.bmp.procurement.ProcurementApi.id;
import static com.bmp.procurement.ProcurementApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.procurement.ProcurementApiIT.Shop;
import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.procurement.api.events.SupplierInvoicePosted;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Procurement keeps its contracts: every operation protected as x-permission says; its events as AsyncAPI says. */
@SpringBootTest(classes = ProcurementTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class ProcurementContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        ProcurementApi api = new ProcurementApi(mvc, "Procurement security");
        String nobody =
                JwtTestTokens.builder().tenant(api.tenant).roles("NOBODY").build();
        ContractSecurity.assertEveryOperationIsProtected("procurement", mvc, context, nobody);
    }

    @Test
    void procurementEventsConformToTheAsyncApiContract() {
        Shop shop = new Shop(mvc, "Procurement events");
        JsonNode eggs = shop.eggDelivery();
        shop.posted(shop.receipt(
                ", \"correctsReceiptId\": \"" + id(eggs) + "\"",
                Shop.receiptLine(shop.small, "piece", "-10", "38.00", "")));
        JsonNode invoice = json(shop.api.post("/api/v1/supplier-invoices", """
                {"supplierId": "%s", "supplierRef": "INV-1", "invoiceDate": "2026-11-23", "subtotal": "36000.00",
                 "tax": "180.00", "receiptIds": ["%s"]}
                """.formatted(shop.supplier, id(eggs))));
        shop.api.action("/api/v1/supplier-invoices/" + id(invoice) + "/post");

        assertThat(events.stream(GoodsReceived.class)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(events.stream(SupplierInvoicePosted.class)).isNotEmpty();
        events.stream(GoodsReceived.class)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "GoodsReceived"));
        events.stream(SupplierInvoicePosted.class)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "SupplierInvoicePosted"));
    }
}
