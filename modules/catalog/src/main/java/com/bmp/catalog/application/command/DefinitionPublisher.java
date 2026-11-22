package com.bmp.catalog.application.command;

import com.bmp.catalog.application.CatalogEvents;
import com.bmp.catalog.domain.model.DefinitionStatus;
import com.bmp.catalog.domain.model.TransformationDefinition;
import com.bmp.catalog.domain.model.Variant;
import com.bmp.catalog.domain.port.DefinitionVersionLog;
import com.bmp.catalog.domain.port.TransformationDefinitionRepository;
import com.bmp.catalog.domain.service.TemplateStrategy;
import com.bmp.catalog.domain.service.TemplateStrategy.DefinitionPlan;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes transformation definition versions (CAT-10): freezes the version in the version log and publishes
 * {@code TransformationDefinitionPublished}. Also creates the definitions templates imply (CAT-09) and re-versions
 * published definitions whose packaging units changed size (CAT-14). Runs inside the caller's transaction.
 */
@Component
class DefinitionPublisher {

    private final TransformationDefinitionRepository definitions;
    private final DefinitionVersionLog versionLog;
    private final CatalogEvents events;
    private final IdGenerator ids;
    private final Clock clock;
    private final JsonMapper json;

    DefinitionPublisher(
            TransformationDefinitionRepository definitions,
            DefinitionVersionLog versionLog,
            CatalogEvents events,
            IdGenerator ids,
            Clock clock,
            JsonMapper json) {
        this.definitions = definitions;
        this.versionLog = versionLog;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    /** Publishes the definition as its next version. */
    TransformationDefinition publish(TransformationDefinition definition) {
        Instant now = clock.instant();
        definition.publish(now);
        TransformationDefinition saved = definitions.saveAndFlush(definition);
        UserId actor = ActorContext.currentOrSystem().userId();
        versionLog.append(saved.id(), saved.defVersion(), now, actor != null ? actor.value() : null, snapshot(saved));
        events.definitionPublished(saved);
        return saved;
    }

    /** Rule CAT-09: creates and publishes the definitions the template implies for a new variant. */
    void createImplied(TemplateStrategy strategy, Variant variant) {
        for (DefinitionPlan plan : strategy.transformations(variant.sku(), variant)) {
            TransformationDefinition definition = TransformationDefinition.draft(
                    ids.newUuid(),
                    TenantContext.require(),
                    plan.code(),
                    plan.name(),
                    plan.kind(),
                    plan.expectedYieldPct(),
                    plan.lines(),
                    ids::newUuid);
            publish(definitions.saveAndFlush(definition));
        }
    }

    /**
     * Rule CAT-14: resizes the lines sized by these packaging units; a published definition gets a new published
     * version with the new quantities (drafts are only resized, and publish later).
     */
    void resize(Map<UUID, BigDecimal> unitSizes) {
        for (TransformationDefinition definition : definitions.findUsingPackagingUnits(unitSizes.keySet())) {
            boolean wasPublished = definition.status() == DefinitionStatus.PUBLISHED;
            if (definition.resizeLines(unitSizes)) {
                if (wasPublished) {
                    publish(definition);
                } else {
                    definitions.saveAndFlush(definition);
                }
            }
        }
    }

    private String snapshot(TransformationDefinition definition) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("code", definition.code());
        snapshot.put("name", definition.name());
        snapshot.put("kind", definition.kind().name());
        BigDecimal yield = definition.expectedYieldPct();
        if (yield != null) {
            snapshot.put("expectedYieldPct", yield);
        }
        snapshot.put("lines", CatalogEvents.lines(definition));
        return json.writeValueAsString(snapshot);
    }

    /** Rule CAT-09: the definitions the template implies for one new packaging unit of an existing variant. */
    void createImpliedFor(TemplateStrategy strategy, Variant variant, UUID unitId) {
        for (DefinitionPlan plan : strategy.transformations(variant.sku(), variant)) {
            if (plan.lines().stream().anyMatch(line -> unitId.equals(line.packagingUnitId()))) {
                publish(definitions.saveAndFlush(TransformationDefinition.draft(
                        ids.newUuid(),
                        TenantContext.require(),
                        plan.code(),
                        plan.name(),
                        plan.kind(),
                        plan.expectedYieldPct(),
                        plan.lines(),
                        ids::newUuid)));
            }
        }
    }

    /** Every definition implied for these variants, for callers that create several variants at once. */
    void createImplied(TemplateStrategy strategy, List<Variant> variants) {
        variants.forEach(variant -> createImplied(strategy, variant));
    }
}
