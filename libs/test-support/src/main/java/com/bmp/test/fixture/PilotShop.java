package com.bmp.test.fixture;

import com.bmp.shared.id.TenantId;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.OpenApiValidation;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * The pilot shop created by {@link PilotShopFixture}: its tenant, owner token, products as the catalog returned them,
 * the seeded price lists and the tax category. Requests sent through it are checked against the contract of the
 * module that serves the path.
 */
public final class PilotShop {

    private final ApiClient client;
    private final TenantId tenant;
    private final String owner;
    private final List<JsonNode> products;
    private final String retailListId;
    private final String wholesaleListId;
    private final String vatCategoryId;

    PilotShop(
            ApiClient client,
            TenantId tenant,
            String owner,
            List<JsonNode> products,
            String retailListId,
            String wholesaleListId,
            String vatCategoryId) {
        this.client = client;
        this.tenant = tenant;
        this.owner = owner;
        this.products = List.copyOf(products);
        this.retailListId = retailListId;
        this.wholesaleListId = wholesaleListId;
        this.vatCategoryId = vatCategoryId;
    }

    public TenantId tenant() {
        return tenant;
    }

    /** A token of the shop owner. */
    public String owner() {
        return owner;
    }

    public String retailListId() {
        return retailListId;
    }

    public String wholesaleListId() {
        return wholesaleListId;
    }

    /** The placeholder VAT category (18 %) of the cooking oil. */
    public String vatCategoryId() {
        return vatCategoryId;
    }

    /** A product by code ({@code EGG}, {@code OIL}, {@code SOYA}, {@code SOYA-LOOSE}, {@code KAROLA}). */
    public JsonNode product(String code) {
        return products.stream()
                .filter(p -> p.path("code").asString().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No product " + code));
    }

    /** A variant of any pilot product by SKU. */
    public JsonNode variant(String sku) {
        for (JsonNode product : products) {
            for (JsonNode variant : product.path("variants")) {
                if (variant.path("sku").asString().equals(sku)) {
                    return variant;
                }
            }
        }
        throw new AssertionError("No variant " + sku);
    }

    /** The id of a variant's packaging unit by code. */
    public static String unit(JsonNode variant, String code) {
        for (JsonNode unit : variant.path("packagingUnits")) {
            if (unit.path("code").asString().equals(code)) {
                return unit.path("id").asString();
            }
        }
        throw new AssertionError(
                "No packaging unit " + code + " on " + variant.path("sku").asString());
    }

    /** A basket line for {@code POST /api/v1/prices:resolve}. */
    public String line(String lineId, String sku, String unitCode, String qty) {
        JsonNode variant = variant(sku);
        return "{\"lineId\": \"" + lineId + "\", \"variantId\": \""
                + variant.path("id").asString() + "\", \"packagingUnitId\": \"" + unit(variant, unitCode)
                + "\", \"qty\": \"" + qty + "\"}";
    }

    public MvcTestResult get(String path) {
        return checked(client.get(path, owner));
    }

    public MvcTestResult post(String path, String body) {
        return checked(client.post(path, owner, body));
    }

    public MvcTestResult put(String path, String body, @Nullable String ifMatch) {
        return checked(client.put(path, owner, body, ifMatch));
    }

    public MvcTestResult patch(String path, String body, String ifMatch) {
        return checked(client.patch(path, owner, body, ifMatch));
    }

    public static JsonNode json(MvcTestResult result) {
        return ApiClient.json(result);
    }

    public static String etag(MvcTestResult result) {
        return Objects.requireNonNull(ApiClient.etag(result), "ETag");
    }

    /** Checks a response against the contract of the module serving its path. */
    static MvcTestResult checked(MvcTestResult result) {
        OpenApiValidation.forModule(
                        module(Objects.requireNonNullElse(result.getRequest().getRequestURI(), "")))
                .assertConforms(result);
        return result;
    }

    private static String module(String path) {
        if (path.startsWith("/api/v1/price") || path.startsWith("/api/v1/tax-")) {
            return "pricing";
        }
        if (path.startsWith("/api/v1/settings")
                || path.startsWith("/api/v1/tenant")
                || path.startsWith("/api/v1/locations")) {
            return "tenant";
        }
        if (path.startsWith("/api/v1/parties") || path.startsWith("/api/v1/customer-groups")) {
            return "party";
        }
        if (path.startsWith("/api/v1/purchase-orders")
                || path.startsWith("/api/v1/goods-receipts")
                || path.startsWith("/api/v1/supplier-invoices")) {
            return "procurement";
        }
        if (path.startsWith("/api/v1/stock")
                || path.startsWith("/api/v1/admin/stock")
                || path.endsWith("/reorder-policy")) {
            return "inventory";
        }
        return "catalog";
    }
}
