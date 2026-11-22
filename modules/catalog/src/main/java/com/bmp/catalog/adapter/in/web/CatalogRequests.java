package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.model.Conversion;
import com.bmp.catalog.adapter.in.web.generated.model.CreateProductRequest;
import com.bmp.catalog.adapter.in.web.generated.model.CreateTransformationDefinitionRequest;
import com.bmp.catalog.adapter.in.web.generated.model.CreateVariantRequest;
import com.bmp.catalog.adapter.in.web.generated.model.DefinedAs;
import com.bmp.catalog.adapter.in.web.generated.model.NewAttribute;
import com.bmp.catalog.adapter.in.web.generated.model.NewPackagingUnit;
import com.bmp.catalog.adapter.in.web.generated.model.NewTransformationLine;
import com.bmp.catalog.adapter.in.web.generated.model.SellMode;
import com.bmp.catalog.adapter.in.web.generated.model.Status;
import com.bmp.catalog.adapter.in.web.generated.model.Tracking;
import com.bmp.catalog.adapter.in.web.generated.model.UpdatePackagingUnitRequest;
import com.bmp.catalog.application.command.ConversionSpec;
import com.bmp.catalog.application.command.CreateProductHandler.CreateProduct;
import com.bmp.catalog.application.command.DefinitionHandlers.LineSpec;
import com.bmp.catalog.application.command.PackagingUnitHandlers.UpdateUnit;
import com.bmp.catalog.application.command.PackagingUnitSpec;
import com.bmp.catalog.application.command.VariantHandlers.CreateVariant;
import com.bmp.catalog.domain.model.AttributeDraft;
import com.bmp.catalog.domain.model.AttributeValueDraft;
import com.bmp.catalog.domain.model.CatalogStatus;
import com.bmp.catalog.domain.model.ConversionSource;
import com.bmp.catalog.domain.model.Direction;
import com.bmp.catalog.domain.model.ProductTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Request DTOs to application commands; validation of formats already happened (Bean Validation, 04 par. 4). */
@Component
class CatalogRequests {

    private final CatalogJson json;

    CatalogRequests(CatalogJson json) {
        this.json = json;
    }

    CreateProduct createProduct(CreateProductRequest request) {
        List<NewAttribute> attributes = orEmpty(request.getAttributes());
        return new CreateProduct(
                request.getCode(),
                request.getName(),
                ProductTemplate.valueOf(request.getTemplate().getValue()),
                request.getCategoryId(),
                request.getBaseUnit(),
                request.getTaxCategoryId(),
                tracking(request.getTracking()),
                json.propertiesJson(request.getProperties()),
                attributes.stream().map(CatalogRequests::attribute).toList(),
                orEmpty(request.getExclusions()),
                request.getStockStates() != null ? List.copyOf(request.getStockStates()) : null,
                sellMode(request.getSellMode()),
                conversions(orEmpty(request.getConversions())),
                orEmpty(request.getPackagingUnits()).stream()
                        .map(CatalogRequests::unit)
                        .toList());
    }

    CreateVariant createVariant(UUID productId, CreateVariantRequest request) {
        List<NewPackagingUnit> units = request.getPackagingUnits();
        return new CreateVariant(
                productId,
                request.getName(),
                request.getSku(),
                request.getAttributeValues(),
                request.getStockStates() != null ? List.copyOf(request.getStockStates()) : null,
                sellMode(request.getSellMode()),
                units != null ? units.stream().map(CatalogRequests::unit).toList() : null);
    }

    String propertiesJson(@Nullable Map<String, String> properties) {
        return json.propertiesJson(properties);
    }

    static AttributeDraft attribute(NewAttribute attribute) {
        return new AttributeDraft(
                attribute.getCode(),
                attribute.getName(),
                attribute.getSort() != null ? attribute.getSort() : 0,
                attribute.getValues().stream()
                        .map(v -> new AttributeValueDraft(v.getCode(), v.getValue(), v.getSort()))
                        .toList());
    }

    static PackagingUnitSpec unit(NewPackagingUnit unit) {
        return new PackagingUnitSpec(
                unit.getCode(),
                unit.getName(),
                decimal(unit.getQtyInBase()),
                definedAs(unit.getDefinedAs()),
                unit.getStockState(),
                unit.getCanBuy(),
                unit.getCanSell(),
                unit.getIsDefaultSell());
    }

    static UpdateUnit updateUnit(UpdatePackagingUnitRequest request) {
        return new UpdateUnit(
                request.getName(),
                decimal(request.getQtyInBase()),
                definedAs(request.getDefinedAs()),
                request.getStockState(),
                request.getCanBuy(),
                request.getCanSell(),
                request.getIsDefaultSell(),
                status(request.getStatus()));
    }

    static List<ConversionSpec> conversions(List<Conversion> conversions) {
        return conversions.stream()
                .map(c -> new ConversionSpec(
                        c.getFromUnit(),
                        c.getToUnit(),
                        new BigDecimal(c.getFactor()),
                        ConversionSource.valueOf(c.getSource().getValue())))
                .toList();
    }

    static List<LineSpec> lines(List<NewTransformationLine> lines) {
        return lines.stream()
                .map(l -> new LineSpec(
                        Direction.valueOf(l.getDirection().getValue()),
                        l.getVariantId(),
                        l.getStockState(),
                        decimal(l.getQtyBase()),
                        l.getPackagingUnitId(),
                        decimal(l.getQty()),
                        decimal(l.getCostSharePct())))
                .toList();
    }

    static List<LineSpec> lines(CreateTransformationDefinitionRequest request) {
        return lines(request.getLines());
    }

    static com.bmp.catalog.domain.model.@Nullable Tracking tracking(@Nullable Tracking tracking) {
        if (tracking == null) {
            return null;
        }
        return new com.bmp.catalog.domain.model.Tracking(
                Boolean.TRUE.equals(tracking.getBatches()),
                Boolean.TRUE.equals(tracking.getExpiry()),
                Boolean.TRUE.equals(tracking.getSerials()),
                Boolean.TRUE.equals(tracking.getDecimalQty()),
                Boolean.TRUE.equals(tracking.getWeighed()));
    }

    static com.bmp.catalog.domain.model.@Nullable SellMode sellMode(@Nullable SellMode sellMode) {
        return sellMode != null ? com.bmp.catalog.domain.model.SellMode.valueOf(sellMode.getValue()) : null;
    }

    static @Nullable CatalogStatus status(@Nullable Status status) {
        return status != null ? CatalogStatus.valueOf(status.getValue()) : null;
    }

    static @Nullable BigDecimal decimal(@Nullable String value) {
        return value != null ? new BigDecimal(value) : null;
    }

    private static PackagingUnitSpec.@Nullable DefinedAsSpec definedAs(@Nullable DefinedAs definedAs) {
        return definedAs != null
                ? new PackagingUnitSpec.DefinedAsSpec(new BigDecimal(definedAs.getValue()), definedAs.getUnit())
                : null;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : List.of();
    }
}
