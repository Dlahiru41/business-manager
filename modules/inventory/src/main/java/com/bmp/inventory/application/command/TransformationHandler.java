package com.bmp.inventory.application.command;

import com.bmp.inventory.application.InventoryEvents;
import com.bmp.inventory.application.InventorySettings;
import com.bmp.inventory.application.StockLedger;
import com.bmp.inventory.application.StockReferences;
import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.DefinitionLine;
import com.bmp.inventory.domain.model.DefinitionRef;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.RunLine;
import com.bmp.inventory.domain.model.StockFlag;
import com.bmp.inventory.domain.model.TransformationRun;
import com.bmp.inventory.domain.policy.NegativeStockPolicy;
import com.bmp.inventory.domain.port.DefinitionStore;
import com.bmp.inventory.domain.port.TransformationRunStore;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.inventory.domain.service.StockLine;
import com.bmp.inventory.domain.service.TransformationPlanner;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.money.CurrencyCode;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a transformation (INV-09, INV-10): opens a can, packs loose goods, makes a recipe. Inputs leave at current
 * cost, outputs come in at their share of it, and the run records the yield variance.
 */
@Service
public class TransformationHandler {

    private static final Logger log = LoggerFactory.getLogger(TransformationHandler.class);

    private final DefinitionStore definitions;
    private final TransformationRunStore runs;
    private final StockLedger ledger;
    private final StockReferences references;
    private final InventorySettings settings;
    private final InventoryEvents events;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters")
    public TransformationHandler(
            DefinitionStore definitions,
            TransformationRunStore runs,
            StockLedger ledger,
            StockReferences references,
            InventorySettings settings,
            InventoryEvents events,
            IdGenerator ids,
            Clock clock) {
        this.definitions = definitions;
        this.runs = runs;
        this.ledger = ledger;
        this.references = references;
        this.settings = settings;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public record RunCommand(
            UUID definitionId,
            UUID locationId,
            @Nullable BigDecimal runs,
            @Nullable List<TransformationPlanner.Actual> outputs,
            @Nullable String note,
            @Nullable Instant occurredAt) {}

    @Transactional
    @Observed(name = "bmp.inventory.transformation")
    public TransformationRun run(RunCommand command) {
        DefinitionRef definition = definitions
                .latest(command.definitionId())
                .orElseThrow(() -> new BusinessRuleViolation(
                        InventoryErrorCode.UNKNOWN_TRANSFORMATION_DEFINITION,
                        Map.of("definitionId", command.definitionId())));
        if (!definition.active()) {
            throw new BusinessRuleViolation(
                    InventoryErrorCode.TRANSFORMATION_DEFINITION_INACTIVE,
                    Map.of("definitionId", definition.definitionId()));
        }
        references.location(command.locationId());
        BigDecimal times = command.runs() != null ? command.runs() : BigDecimal.ONE;
        List<TransformationPlanner.Output> outputs =
                TransformationPlanner.outputs(definition, times, command.outputs());
        Instant at = command.occurredAt() != null ? command.occurredAt() : clock.instant();
        UUID runId = ids.newUuid();
        boolean mayGoNegative = settings.negativeStockPolicy() == NegativeStockPolicy.ALLOW_AND_FLAG;

        List<StockLine> inLines = new ArrayList<>();
        List<BalanceKey> keys = new ArrayList<>();
        for (DefinitionLine input : definition.inputs()) {
            BalanceKey key = BalanceKey.of(input.variantId(), command.locationId(), input.stockState());
            keys.add(key);
            inLines.add(new StockLine(
                    key,
                    MovementType.TRANSFORM_OUT,
                    TransformationPlanner.consumed(input, times).negate(),
                    null,
                    mayGoNegative,
                    "TRANSFORMATION",
                    runId,
                    null,
                    definition.kind(),
                    at));
        }
        outputs.forEach(o -> keys.add(BalanceKey.of(
                o.line().variantId(), command.locationId(), o.line().stockState())));
        ledger.lock(keys);
        List<PlannedMovement> consumed = ledger.post(inLines);
        BigDecimal inputCost =
                consumed.stream().map(m -> m.costAmount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = settings.tenant().currency();
        List<BigDecimal> costs = TransformationPlanner.costs(inputCost, outputs, CurrencyCode.of(currency));
        List<StockLine> outLines = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            TransformationPlanner.Output output = outputs.get(i);
            outLines.add(new StockLine(
                    BalanceKey.of(
                            output.line().variantId(),
                            command.locationId(),
                            output.line().stockState()),
                    MovementType.TRANSFORM_IN,
                    output.actual(),
                    costs.get(i),
                    true,
                    "TRANSFORMATION",
                    runId,
                    null,
                    definition.kind(),
                    at));
        }
        List<PlannedMovement> produced = ledger.post(outLines);
        List<PlannedMovement> made =
                produced.stream().filter(m -> !m.isCorrection()).toList();

        List<RunLine> inputLines = consumed.stream()
                .map(m -> new RunLine(
                        m.id(),
                        m.key().variantId(),
                        m.key().stockState(),
                        null,
                        m.qtyBase().negate(),
                        null,
                        m.costAmount().negate(),
                        m.unitCost()))
                .toList();
        List<RunLine> outputLines = new ArrayList<>();
        for (int i = 0; i < made.size(); i++) {
            PlannedMovement m = made.get(i);
            TransformationPlanner.Output output = outputs.get(i);
            outputLines.add(new RunLine(
                    m.id(),
                    m.key().variantId(),
                    m.key().stockState(),
                    output.expected(),
                    m.qtyBase(),
                    output.sharePct(),
                    m.costAmount(),
                    m.costAmount().divide(m.qtyBase(), 6, RoundingMode.HALF_UP)));
        }
        var user = ActorContext.currentOrSystem().userId();
        TransformationRun run = new TransformationRun(
                runId,
                definition.definitionId(),
                definition.defVersion(),
                definition.kind(),
                command.locationId(),
                times,
                inputLines,
                outputLines,
                TransformationPlanner.yieldVariance(outputs),
                inputCost,
                currency,
                command.note(),
                at,
                user == null ? null : user.value(),
                consumed.stream().anyMatch(m -> m.flags().contains(StockFlag.NEGATIVE_STOCK)));
        runs.insert(run);
        events.transformed(run, consumed, made);
        log.atInfo()
                .addKeyValue("runId", run.id())
                .addKeyValue("kind", run.kind())
                .addKeyValue("yieldVariance", run.yieldVarianceQty())
                .log("Transformation run");
        return run;
    }
}
