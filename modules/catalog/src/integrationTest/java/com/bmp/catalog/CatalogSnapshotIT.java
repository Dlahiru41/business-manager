package com.bmp.catalog;

import static com.bmp.catalog.CatalogApi.EGGS;
import static com.bmp.catalog.CatalogApi.OIL;
import static com.bmp.catalog.CatalogApi.etag;
import static com.bmp.catalog.CatalogApi.json;
import static com.bmp.catalog.CatalogApi.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.api.CatalogQueries;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.VariantId;
import com.bmp.test.persistence.WithPostgres;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** The till snapshot: full, deltas since a version, 304 when unchanged (CAT-13); and the module read API. */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class CatalogSnapshotIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    CatalogQueries catalog;

    @Test
    void cat13SnapshotsCarryTheWholeCatalogThenOnlyChanges() {
        CatalogApi api = new CatalogApi(mvc, "Snapshot");
        JsonNode eggs = api.create(EGGS);

        MvcTestResult full = api.get("/api/v1/catalog/snapshot");
        long version = json(full).path("version").asLong();
        assertThat(full).hasStatusOk().hasHeader("ETag", "W/\"" + version + "\"");
        assertThat(json(full).path("full").asBoolean()).isTrue();
        assertThat(json(full).path("products")).hasSize(1);
        assertThat(json(full).path("variants")).hasSize(12);
        assertThat(json(full).path("units")).hasSize(9);
        assertThat(api.client.getIfNoneMatch("/api/v1/catalog/snapshot", api.owner, etag(full)))
                .hasStatus(304);

        api.create(OIL);
        String redL = variant(eggs, "EGG-RED-L").path("id").asString();
        api.action("/api/v1/variants/" + redL + "/deactivate");

        JsonNode delta = json(api.get("/api/v1/catalog/snapshot?sinceVersion=" + version));
        assertThat(delta.path("full").asBoolean()).isFalse();
        assertThat(delta.path("sinceVersion").asLong()).isEqualTo(version);
        assertThat(delta.path("version").asLong()).isGreaterThan(version);
        assertThat(delta.path("products"))
                .extracting(p -> p.path("code").asString())
                .containsExactly("OIL");
        assertThat(delta.path("variants"))
                .extracting(
                        v -> v.path("sku").asString() + ":" + v.path("status").asString())
                .containsExactlyInAnyOrder("OIL:ACTIVE", "EGG-RED-L:INACTIVE");
        assertThat(json(api.get("/api/v1/catalog/snapshot?sinceVersion="
                                + delta.path("version").asLong()))
                        .path("variants"))
                .isEmpty();
    }

    @Test
    void theModuleApiReadsVariantsUnitsAndTheCatalogVersion() {
        CatalogApi api = new CatalogApi(mvc, "Catalog queries");
        JsonNode oil = api.create(OIL);
        JsonNode variant = variant(oil, "OIL");
        UUID id = UUID.fromString(variant.path("id").asString());
        UUID can = UUID.fromString(CatalogApi.unit(variant, "can").path("id").asString());
        api.post("/api/v1/packaging-units/" + can + "/barcodes", "{\"code\": \"4792173000035\"}");

        TenantContext.runWith(api.tenant, () -> {
            var info = catalog.findVariant(VariantId.of(id)).orElseThrow();
            assertThat(info.baseUnit()).isEqualTo("ml");
            assertThat(info.sellable()).isTrue();
            assertThat(info.packagingUnits()).hasSize(6);
            assertThat(catalog.findPackagingUnit(com.bmp.shared.id.PackagingUnitId.of(can))
                            .orElseThrow()
                            .qtyInBase())
                    .isEqualByComparingTo("19565.28");
            assertThat(catalog.findByBarcode("4792173000035")).map(v -> v.sku()).hasValue("OIL");
            assertThat(catalog.findVariant(VariantId.of(UUID.randomUUID()))).isEmpty();
            assertThat(catalog.catalogVersion()).isPositive();
        });
    }
}
