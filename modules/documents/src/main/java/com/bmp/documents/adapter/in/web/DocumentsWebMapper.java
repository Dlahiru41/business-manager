package com.bmp.documents.adapter.in.web;

import com.bmp.documents.adapter.in.web.generated.model.PrintAgent;
import com.bmp.documents.adapter.in.web.generated.model.PrintAgentPagePage;
import com.bmp.documents.adapter.in.web.generated.model.PrintAgentStatus;
import com.bmp.documents.adapter.in.web.generated.model.PrintJob;
import com.bmp.documents.adapter.in.web.generated.model.PrintJobStatus;
import com.bmp.documents.adapter.in.web.generated.model.ReceiptModel;
import com.bmp.documents.adapter.in.web.generated.model.ReceiptTemplate;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import tools.jackson.databind.json.JsonMapper;

/** Print agents, jobs, templates and receipts to and from their API shapes; amounts are strings (02 §5). */
@Mapper(componentModel = "spring")
abstract class DocumentsWebMapper {

    private final JsonMapper json = JsonMapper.builder().build();

    PrintAgent agent(com.bmp.documents.domain.model.PrintAgent a) {
        return new PrintAgent()
                .id(a.id())
                .locationId(a.locationId())
                .name(a.name())
                .printerModel(a.printerModel())
                .paperWidthMm(a.paperWidthMm())
                .status(PrintAgentStatus.fromValue(a.status()))
                .lastSeenAt(time(a.lastSeenAt()))
                .createdAt(time(a.createdAt()));
    }

    PrintJob job(com.bmp.documents.domain.model.PrintJob j) {
        Long version = j.templateVersion();
        return new PrintJob()
                .id(j.id())
                .agentId(j.agentId())
                .documentType(PrintJob.DocumentTypeEnum.fromValue(j.documentType()))
                .sourceId(j.sourceId())
                .status(PrintJobStatus.fromValue(j.status()))
                .attempts(j.attempts())
                .receipt(json.readValue(j.receipt(), ReceiptModel.class))
                .templateVersion(version == null ? null : Math.toIntExact(version))
                .error(j.error())
                .createdAt(utc(j.createdAt()))
                .completedAt(time(j.completedAt()));
    }

    ReceiptTemplate template(com.bmp.receipt.ReceiptTemplate t) {
        return new ReceiptTemplate()
                .version(Math.toIntExact(t.version()))
                .shopName(t.shopName())
                .addressLines(t.addressLines())
                .phone(t.phone())
                .taxNumber(t.taxNumber())
                .footerLines(t.footerLines())
                .paperWidthMm(ReceiptTemplate.PaperWidthMmEnum.fromValue(t.paperWidthMm()))
                .showQr(t.showQr())
                .openDrawerOnCash(t.openDrawerOnCash())
                .language(ReceiptTemplate.LanguageEnum.fromValue(t.language()));
    }

    /** A receipt the caller sends (a till rendering before upload); its time is local to the tenant. */
    com.bmp.receipt.ReceiptModel receipt(ReceiptModel r, ZoneId zone) {
        return new com.bmp.receipt.ReceiptModel(
                r.getBillId(),
                r.getBillNumber(),
                LocalDateTime.parse(r.getIssuedAt()).atZone(zone).toInstant(),
                r.getCashier(),
                r.getRegister(),
                r.getLines().stream()
                        .map(l -> new com.bmp.receipt.ReceiptModel.Line(
                                l.getName(),
                                new BigDecimal(l.getQty()),
                                l.getUnit(),
                                new BigDecimal(l.getUnitPrice()),
                                new BigDecimal(l.getLineTotal()),
                                decimal(l.getDiscount())))
                        .toList(),
                decimal(r.getDiscountTotal()),
                decimal(r.getTaxTotal()),
                new BigDecimal(r.getTotal()),
                r.getTenders() == null
                        ? List.of()
                        : r.getTenders().stream()
                                .map(t -> new com.bmp.receipt.ReceiptModel.Tender(
                                        t.getMethod(), new BigDecimal(t.getAmount())))
                                .toList(),
                decimal(r.getChange()),
                r.getCustomer(),
                r.getCurrency(),
                Boolean.TRUE.equals(r.getCopy()),
                Boolean.TRUE.equals(r.getCashTendered()));
    }

    PrintAgentPagePage page(PageResponse<?> page) {
        return new PrintAgentPagePage()
                .limit(page.page().limit())
                .nextCursor(page.page().nextCursor())
                .hasMore(page.page().hasMore());
    }

    private static BigDecimal decimal(@Nullable String value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static @Nullable OffsetDateTime time(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
