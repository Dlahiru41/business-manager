package com.bmp.documents.application;

import com.bmp.receipt.ReceiptModel;
import com.bmp.receipt.ReceiptTemplate;
import com.bmp.sales.api.SalesQueries;
import com.bmp.sales.api.dto.BillReceipt;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.ReceiptTemplateInfo;
import com.bmp.tenant.api.dto.TenantSummary;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds what a receipt shows (DOC-02, DOC-06): the bill from sales, the template from the tenant's default receipt
 * template (header lines: the shop name, then the address; footer lines; switches {@code showQr} and
 * {@code openDrawerOnCash}), the tax number from the tax settings and the time zone from the tenant.
 */
@Component
public class ReceiptSource {

    private final TenantQueries tenants;
    private final SalesQueries sales;
    private final JsonMapper json;

    public ReceiptSource(TenantQueries tenants, SalesQueries sales, JsonMapper json) {
        this.tenants = tenants;
        this.sales = sales;
        this.json = json;
    }

    /** The template at a paper width; the tenant's own width when {@code paperWidthMm} is null. */
    public ReceiptTemplate template(@Nullable Integer paperWidthMm) {
        TenantSummary tenant = tenants.currentTenant()
                .orElseThrow(() -> new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING, Map.of(), null));
        Optional<ReceiptTemplateInfo> template = tenants.defaultReceiptTemplate();
        List<String> header = lines(template.map(ReceiptTemplateInfo::header).orElse("{}"));
        List<String> footer = lines(template.map(ReceiptTemplateInfo::footer).orElse("{}"));
        JsonNode fields =
                json.readTree(template.map(ReceiptTemplateInfo::fields).orElse("{}"));
        String tax = tenants.setting("tax", "taxRegistrationNumber")
                .map(json::readTree)
                .filter(JsonNode::isString)
                .map(JsonNode::asString)
                .orElse(null);
        String phone = fields.path("phone").isString() ? fields.path("phone").asString() : null;
        return new ReceiptTemplate(
                template.map(ReceiptTemplateInfo::version).orElse(0L),
                header.isEmpty() ? tenant.name() : header.getFirst(),
                header.size() > 1 ? header.subList(1, header.size()) : List.of(),
                phone,
                tax,
                footer,
                paperWidthMm != null
                        ? paperWidthMm
                        : template.map(ReceiptTemplateInfo::paperWidthMm).orElse(80),
                fields.path("showQr").asBoolean(false),
                fields.path("openDrawerOnCash").asBoolean(true),
                template.map(ReceiptTemplateInfo::language).orElse("en"),
                ZoneId.of(tenant.timeZone()));
    }

    /** A completed bill's receipt; empty for drafts, voided and unknown bills. */
    public Optional<ReceiptModel> bill(UUID billId, boolean copy) {
        return sales.findReceipt(billId).map(b -> model(b, copy));
    }

    /** The location a bill was sold at. */
    public Optional<UUID> locationOf(UUID billId) {
        return sales.findReceipt(billId).map(BillReceipt::locationId);
    }

    private static ReceiptModel model(BillReceipt b, boolean copy) {
        BigDecimal change =
                b.tenders().stream().map(BillReceipt.Tender::change).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceiptModel(
                b.billId(),
                b.number(),
                b.completedAt(),
                null,
                b.registerCode(),
                b.lines().stream()
                        .map(l -> new ReceiptModel.Line(
                                l.name(), l.qty(), l.unit(), l.unitPrice(), l.lineTotal(), l.discount()))
                        .toList(),
                b.discountTotal(),
                b.taxTotal(),
                b.grandTotal(),
                b.tenders().stream()
                        .map(t -> new ReceiptModel.Tender(t.method(), t.amount()))
                        .toList(),
                change,
                b.customer(),
                b.currency(),
                copy,
                b.tenders().stream().anyMatch(t -> t.method().equals("CASH")));
    }

    private List<String> lines(String object) {
        List<String> lines = new ArrayList<>();
        json.readTree(object).path("lines").forEach(line -> {
            if (line.isString() && !line.asString().isBlank()) {
                lines.add(line.asString());
            }
        });
        return lines;
    }
}
