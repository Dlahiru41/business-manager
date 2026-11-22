package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.application.query.DefinitionView;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.Direction;
import com.bmp.catalog.domain.model.LineDraft;
import com.bmp.catalog.domain.model.PackagingUnit;
import com.bmp.catalog.domain.model.StockStates;
import com.bmp.catalog.domain.model.TransformationDefinition;
import com.bmp.catalog.domain.model.TransformationKind;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.TransformationDefinitionRepository;
import com.bmp.catalog.domain.port.VariantRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, edits, publishes and retires transformation definitions (CAT-10, CAT-11). Lines refer to variants of the
 * same tenant, draw from their stock states and may be sized by one of their packaging units.
 */
@Service
public class DefinitionHandlers {

    private final CatalogLoader loader;
    private final TransformationDefinitionRepository definitions;
    private final VariantRepository variants;
    private final DefinitionPublisher publisher;
    private final CatalogEvents events;
    private final IdGenerator ids;

    public DefinitionHandlers(
            CatalogLoader loader,
            TransformationDefinitionRepository definitions,
            VariantRepository variants,
            DefinitionPublisher publisher,
            CatalogEvents events,
            IdGenerator ids) {
        this.loader = loader;
        this.definitions = definitions;
        this.variants = variants;
        this.publisher = publisher;
        this.events = events;
        this.ids = ids;
    }

    /**
     * A line as the request gives it: {@code qtyBase}, or {@code qty} of a packaging unit (default 1).
     *
     * @param stockState defaults to the packaging unit state, else the first state of the variant
     */
    public record LineSpec(
            Direction direction,
            UUID variantId,
            @Nullable String stockState,
            @Nullable BigDecimal qtyBase,
            @Nullable UUID packagingUnitId,
            @Nullable BigDecimal qty,
            @Nullable BigDecimal costSharePct) {}

    public record CreateDefinition(
            String code,
            String name,
            TransformationKind kind,
            @Nullable BigDecimal expectedYieldPct,
            List<LineSpec> lines) {}

    /** PATCH semantics; {@code lines} replaces all lines when given. */
    public record UpdateDefinition(
            UUID definitionId,
            long expectedVersion,
            @Nullable String name,
            @Nullable BigDecimal expectedYieldPct,
            @Nullable List<LineSpec> lines) {}

    @Transactional
    @Observed(name = "bmp.catalog.create-definition")
    public DefinitionView create(CreateDefinition command) {
        TransformationDefinition definition = TransformationDefinition.draft(
                ids.newUuid(),
                TenantContext.require(),
                command.code(),
                command.name(),
                command.kind(),
                command.expectedYieldPct(),
                lines(command.lines()),
                ids::newUuid);
        return DefinitionView.of(definitions.saveAndFlush(definition));
    }

    @Transactional
    @Observed(name = "bmp.catalog.update-definition")
    public DefinitionView update(UpdateDefinition command) {
        TransformationDefinition definition = loader.definition(command.definitionId());
        IfMatch.check(command.expectedVersion(), definition.version());
        List<LineSpec> lines = command.lines();
        definition.edit(command.name(), command.expectedYieldPct(), lines != null ? lines(lines) : null, ids::newUuid);
        return DefinitionView.of(definitions.saveAndFlush(definition));
    }

    /** Rule CAT-10: publishes the draft as the next immutable version. */
    @Transactional
    @Observed(name = "bmp.catalog.publish-definition")
    public DefinitionView publish(UUID definitionId) {
        return DefinitionView.of(publisher.publish(loader.definition(definitionId)));
    }

    /** Rule CAT-11: retired, never deleted; idempotent. */
    @Transactional
    @Observed(name = "bmp.catalog.retire-definition")
    public DefinitionView retire(UUID definitionId) {
        TransformationDefinition definition = loader.definition(definitionId);
        if (!definition.retire()) {
            return DefinitionView.of(definition);
        }
        TransformationDefinition saved = definitions.saveAndFlush(definition);
        events.definitionRetired(saved);
        return DefinitionView.of(saved);
    }

    private List<LineDraft> lines(List<LineSpec> specs) {
        Map<UUID, Variant> known =
                variants
                        .findAllById(specs.stream()
                                .map(LineSpec::variantId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(v -> v.id().value(), Function.identity()));
        List<LineDraft> drafts = new ArrayList<>();
        for (LineSpec spec : specs) {
            Variant variant = known.get(spec.variantId());
            if (variant == null) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.UNKNOWN_VARIANT, Map.of("variantId", spec.variantId()));
            }
            drafts.add(line(spec, variant));
        }
        return drafts;
    }

    private static LineDraft line(LineSpec spec, Variant variant) {
        UUID unitId = spec.packagingUnitId();
        PackagingUnit unit = unitId != null
                ? variant.packagingUnit(unitId)
                        .orElseThrow(() -> new BusinessRuleViolation(
                                CatalogErrorCode.UNKNOWN_VARIANT, Map.of("packagingUnitId", unitId)))
                : null;
        BigDecimal qtyBase = spec.qtyBase();
        BigDecimal qty = spec.qty();
        if (unit != null) {
            if (qtyBase != null) {
                throw oneOf();
            }
            qty = qty != null ? qty : BigDecimal.ONE;
            qtyBase = unit.toBase(qty);
        } else if (qtyBase == null || qty != null) {
            throw oneOf();
        }
        String state = spec.stockState() != null
                ? spec.stockState()
                : unit != null ? unit.stockState() : variant.stockStates().getFirst();
        StockStates.requireIn(variant.stockStates(), state, variant.sku());
        return new LineDraft(spec.direction(), spec.variantId(), state, qtyBase, unitId, qty, spec.costSharePct());
    }

    private static ValidationFailure oneOf() {
        return new ValidationFailure(
                List.of(new FieldError("lines", "ONE_OF", "give qtyBase, or packagingUnitId with an optional qty")));
    }
}
