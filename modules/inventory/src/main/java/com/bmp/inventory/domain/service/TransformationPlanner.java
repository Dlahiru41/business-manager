package com.bmp.inventory.domain.service;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.DefinitionLine;
import com.bmp.inventory.domain.model.DefinitionRef;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.money.Allocation;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Rule INV-09: a run consumes the definition's IN lines × runs and yields the actual outputs given (default the
 * expected ones); the input cost is shared among the outputs by {@code costSharePct}, largest remainder, so
 * Σ output cost = Σ input cost exactly.
 */
public final class TransformationPlanner {

    private static final int QTY_SCALE = 6;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TransformationPlanner() {}

    /** An actual output a request names. */
    public record Actual(UUID variantId, String stockState, BigDecimal qtyBase) {}

    /** An OUT line with what was expected and what came out. */
    public record Output(DefinitionLine line, BigDecimal expected, BigDecimal actual, BigDecimal sharePct) {}

    /** An IN line's quantity for the runs. */
    public static BigDecimal consumed(DefinitionLine input, BigDecimal runs) {
        return input.qtyBase().multiply(runs).setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    /** The outputs of the runs; the actual ones must name each OUT line once, with a positive quantity. */
    public static List<Output> outputs(DefinitionRef definition, BigDecimal runs, @Nullable List<Actual> actual) {
        List<DefinitionLine> lines = definition.outputs();
        if (actual != null && actual.size() != lines.size()) {
            throw invalidOutputs(definition);
        }
        List<Output> outputs = new ArrayList<>();
        for (DefinitionLine line : lines) {
            BigDecimal expected = consumed(line, runs);
            BigDecimal obtained = expected;
            if (actual != null) {
                List<Actual> named = actual.stream()
                        .filter(a -> a.variantId().equals(line.variantId())
                                && a.stockState().equals(line.stockState()))
                        .toList();
                if (named.size() != 1 || named.getFirst().qtyBase().signum() <= 0) {
                    throw invalidOutputs(definition);
                }
                obtained = named.getFirst().qtyBase().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            }
            BigDecimal share = line.costSharePct() != null ? line.costSharePct() : HUNDRED;
            outputs.add(new Output(line, expected, obtained, share));
        }
        return outputs;
    }

    /** Each output's part of the input cost, summing exactly to it. */
    public static List<BigDecimal> costs(BigDecimal inputCost, List<Output> outputs, CurrencyCode currency) {
        List<Money> parts = Allocation.largestRemainder(
                Money.of(inputCost, currency),
                outputs.stream().map(Output::sharePct).toList());
        return parts.stream().map(Money::amount).toList();
    }

    /** Σ actual − Σ expected output. */
    public static BigDecimal yieldVariance(List<Output> outputs) {
        return outputs.stream().map(o -> o.actual().subtract(o.expected())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BusinessRuleViolation invalidOutputs(DefinitionRef definition) {
        return new BusinessRuleViolation(
                InventoryErrorCode.INVALID_OUTPUTS,
                "Actual outputs must name each OUT line once",
                Map.of("definitionId", definition.definitionId()));
    }
}
