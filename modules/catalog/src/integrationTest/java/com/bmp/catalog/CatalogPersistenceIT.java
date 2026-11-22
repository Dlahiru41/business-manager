package com.bmp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.catalog.domain.model.AttributeDraft;
import com.bmp.catalog.domain.model.AttributeValueDraft;
import com.bmp.catalog.domain.model.ConversionSource;
import com.bmp.catalog.domain.model.DefinedQuantity;
import com.bmp.catalog.domain.model.Dimension;
import com.bmp.catalog.domain.model.Exclusion;
import com.bmp.catalog.domain.model.NewProduct;
import com.bmp.catalog.domain.model.NewVariant;
import com.bmp.catalog.domain.model.PackagingUnitDraft;
import com.bmp.catalog.domain.model.Product;
import com.bmp.catalog.domain.model.ProductConversion;
import com.bmp.catalog.domain.model.ProductTemplate;
import com.bmp.catalog.domain.model.SellMode;
import com.bmp.catalog.domain.model.Tracking;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.ProductRepository;
import com.bmp.catalog.domain.port.UnitRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.VariantId;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The catalog schema: row-level security everywhere, shared standard units, aggregates round-tripping (T01-03). */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class CatalogPersistenceIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ProductRepository products;

    @Autowired
    VariantRepository variants;

    @Autowired
    UnitRepository units;

    @Autowired
    CatalogRevisions revisions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T in(TenantId tenant, Supplier<T> work) {
        return Objects.requireNonNull(TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get())));
    }

    @Test
    void catalogSchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(List.of("catalog"), List.of("catalog.transformation_definition_version"));
    }

    @Test
    void cat01StandardUnitsAreSharedAndOwnUnitsStayWithTheirTenant() {
        ApiClient api = new ApiClient(mvc);
        TenantId a = api.createTenant("Units A " + UUID.randomUUID());
        TenantId b = api.createTenant("Units B " + UUID.randomUUID());
        UUID bushel = UUID.randomUUID();
        in(a, () -> {
            units.insert(UnitOfMeasure.custom(
                    bushel, a.value(), "bushel", "Bushel", Dimension.VOLUME, new BigDecimal("35.2391")));
            return true;
        });

        List<String> seenByA =
                in(a, () -> units.findAll().stream().map(UnitOfMeasure::code).toList());
        List<String> seenByB =
                in(b, () -> units.findAll().stream().map(UnitOfMeasure::code).toList());

        assertThat(seenByB).containsExactly("cm", "dozen", "g", "kg", "l", "m", "ml", "pack", "piece");
        assertThat(seenByA).endsWith("bushel").hasSize(10);
        assertThat(in(a, () -> units.findByCode("KG").orElseThrow().toSiFactor()))
                .isEqualByComparingTo("1");
        assertThat(in(b, () -> units.findById(bushel))).isEmpty();
        assertThat(in(b, () -> units.findByCode("bushel"))).isEmpty();
    }

    @Test
    void aggregatesRoundTripAndStayInvisibleToOtherTenants() {
        ApiClient api = new ApiClient(mvc);
        TenantId a = api.createTenant("Persistence A " + UUID.randomUUID());
        TenantId b = api.createTenant("Persistence B " + UUID.randomUUID());
        UUID kg = in(a, () -> units.findByCode("kg").orElseThrow().id());
        UUID ml = in(a, () -> units.findByCode("ml").orElseThrow().id());
        ProductId productId = ProductId.of(UUID.randomUUID());
        VariantId variantId = VariantId.of(UUID.randomUUID());

        in(a, () -> {
            Product oil = Product.create(
                    productId,
                    a,
                    new NewProduct(
                            "OIL",
                            "Cooking oil",
                            ProductTemplate.BULK_BREAK,
                            ml,
                            null,
                            null,
                            Tracking.measured(),
                            "{\"origin\": \"LK\"}",
                            List.of(new AttributeDraft(
                                    "GRADE", "Grade", 1, List.of(new AttributeValueDraft(null, "Premium", null))))),
                    UUID::randomUUID);
            oil.replaceConversions(
                    List.of(new ProductConversion(kg, ml, new BigDecimal("1086.96"), ConversionSource.DENSITY)));
            oil.replaceExclusions(List.of(Exclusion.of(oil.resolveValues(Map.of("GRADE", "PREMIUM"), false))));
            oil.touch(revisions.next());
            products.saveAndFlush(oil);
            Variant variant = Variant.create(
                    variantId,
                    a,
                    productId,
                    new NewVariant(
                            "OIL",
                            "Cooking oil",
                            List.of(),
                            List.of("SEALED", "OPEN"),
                            SellMode.BREAK_ON_SALE,
                            List.of(
                                    new PackagingUnitDraft(
                                            "ml",
                                            "Millilitre",
                                            BigDecimal.ONE,
                                            null,
                                            "OPEN",
                                            true,
                                            false,
                                            false,
                                            false),
                                    new PackagingUnitDraft(
                                            "can",
                                            "Can 18 kg",
                                            new BigDecimal("19565.28"),
                                            new DefinedQuantity(new BigDecimal("18"), kg),
                                            "SEALED",
                                            false,
                                            true,
                                            true,
                                            false))),
                    UUID::randomUUID);
            UUID can = variant.packagingUnits().getLast().id();
            variant.addBarcode(can, "4792173000011");
            variant.touch(revisions.next());
            variants.saveAndFlush(variant);
            return true;
        });

        Variant loaded = in(a, () -> {
            Variant v = variants.findByBarcode("4792173000011").orElseThrow();
            org.hibernate.Hibernate.initialize(v.packagingUnits().getLast().barcodes());
            return v;
        });
        Product product = in(a, () -> {
            Product p = products.findById(productId.value()).orElseThrow();
            p.axes().forEach(t -> org.hibernate.Hibernate.initialize(t.values()));
            org.hibernate.Hibernate.initialize(p.exclusions());
            org.hibernate.Hibernate.initialize(p.conversions());
            return p;
        });

        assertThat(loaded.id()).isEqualTo(variantId);
        assertThat(loaded.stockStates()).containsExactly("SEALED", "OPEN");
        assertThat(loaded.packagingUnits().getLast().definedAs()).isNotNull();
        assertThat(loaded.packagingUnits().getLast().qtyInBase()).isEqualByComparingTo("19565.28");
        assertThat(product.conversions()).hasSize(1);
        assertThat(product.exclusions()).hasSize(1);
        assertThat(product.propertiesJson()).contains("origin");
        assertThat(in(a, () -> revisions.current())).isEqualTo(2);

        assertThat(in(b, () -> products.findById(productId.value()))).isEmpty();
        assertThat(in(b, () -> variants.findBySku("OIL"))).isEmpty();
        assertThat(in(b, () -> variants.findByBarcode("4792173000011"))).isEmpty();
        assertThat(in(b, () -> revisions.current())).isZero();
        for (String table : List.of(
                "product",
                "product_conversion",
                "attribute_type",
                "attribute_value",
                "variant_exclusion",
                "variant",
                "packaging_unit",
                "barcode",
                "catalog_revision")) {
            assertThat(in(
                            b,
                            () -> jdbc.sql("SELECT count(*) FROM catalog." + table + " WHERE tenant_id = :a")
                                    .param("a", a.value())
                                    .query(Long.class)
                                    .single()))
                    .as(table)
                    .isZero();
        }
    }

    @Test
    void collectionRowsTakeTheTransactionTenantAndFailWithoutOne() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(
                                s -> jdbc.sql("INSERT INTO catalog.barcode (code, packaging_unit_id) VALUES ('1', :id)")
                                        .param("id", UUID.randomUUID())
                                        .update()))
                .hasStackTraceContaining("TENANT_CONTEXT_MISSING");
    }
}
