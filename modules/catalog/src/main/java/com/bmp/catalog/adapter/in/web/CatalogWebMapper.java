package com.bmp.catalog.adapter.in.web;

import com.bmp.catalog.adapter.in.web.generated.model.AttributeType;
import com.bmp.catalog.adapter.in.web.generated.model.AttributeValue;
import com.bmp.catalog.adapter.in.web.generated.model.CatalogSnapshot;
import com.bmp.catalog.adapter.in.web.generated.model.Category;
import com.bmp.catalog.adapter.in.web.generated.model.Conversion;
import com.bmp.catalog.adapter.in.web.generated.model.DefinedAs;
import com.bmp.catalog.adapter.in.web.generated.model.GenerateVariantsResult;
import com.bmp.catalog.adapter.in.web.generated.model.GeneratedVariant;
import com.bmp.catalog.adapter.in.web.generated.model.PackagingUnit;
import com.bmp.catalog.adapter.in.web.generated.model.Product;
import com.bmp.catalog.adapter.in.web.generated.model.ProductTemplate;
import com.bmp.catalog.adapter.in.web.generated.model.Tracking;
import com.bmp.catalog.adapter.in.web.generated.model.TransformationDefinition;
import com.bmp.catalog.adapter.in.web.generated.model.TransformationLine;
import com.bmp.catalog.adapter.in.web.generated.model.Unit;
import com.bmp.catalog.adapter.in.web.generated.model.Variant;
import com.bmp.catalog.adapter.in.web.generated.model.VariantAttributeValue;
import com.bmp.catalog.application.query.AttributeTypeView;
import com.bmp.catalog.application.query.CategoryView;
import com.bmp.catalog.application.query.DefinitionView;
import com.bmp.catalog.application.query.GenerationView;
import com.bmp.catalog.application.query.PackagingUnitView;
import com.bmp.catalog.application.query.ProductView;
import com.bmp.catalog.application.query.SnapshotView;
import com.bmp.catalog.application.query.TemplateView;
import com.bmp.catalog.application.query.UnitView;
import com.bmp.catalog.application.query.VariantView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Catalog read models to API representations; decimals become plain strings without trailing zeros. */
@Mapper(componentModel = "spring", uses = CatalogJson.class, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface CatalogWebMapper {

    Unit toDto(UnitView view);

    Category toDto(CategoryView view);

    @Mapping(target = "properties", source = "propertiesJson")
    Product toDto(ProductView view);

    Tracking toDto(com.bmp.catalog.domain.model.Tracking tracking);

    Conversion toDto(ProductView.Conversion conversion);

    AttributeType toDto(AttributeTypeView view);

    AttributeValue toDto(AttributeTypeView.Value value);

    Variant toDto(VariantView view);

    VariantAttributeValue toDto(VariantView.AttributeValue value);

    @Mapping(target = "isBase", expression = "java(view.isBase())")
    @Mapping(target = "isDefaultSell", expression = "java(view.isDefaultSell())")
    PackagingUnit toDto(PackagingUnitView view);

    DefinedAs toDto(PackagingUnitView.DefinedAs definedAs);

    TransformationDefinition toDto(DefinitionView view);

    TransformationLine toDto(DefinitionView.Line line);

    @Mapping(
            target = "code",
            expression =
                    "java(com.bmp.catalog.adapter.in.web.generated.model.ProductTemplateCode.valueOf(view.code()))")
    ProductTemplate toDto(TemplateView view);

    GenerateVariantsResult toDto(GenerationView view);

    GeneratedVariant toDto(GenerationView.Generated generated);

    CatalogSnapshot toDto(SnapshotView view);

    default @Nullable OffsetDateTime toOffsetDateTime(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /** Decimal strings, never numbers (02 par. 5): {@code 19565.280000} becomes {@code 19565.28}. */
    default @Nullable String decimal(@Nullable BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
