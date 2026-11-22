package com.bmp.catalog.domain.service;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Rule CAT-05: the variant matrix is the cartesian product of a product's attribute values minus its exclusions.
 * Regenerating adds missing combinations only; it never deletes a variant, and it deactivates active variants whose
 * combination is now excluded (inventory will add a "no stock" check in phase 02).
 */
public final class VariantMatrixGenerator {

    /** Upper bound of combinations of one product, so a typo cannot create thousands of SKUs. */
    public static final int MAX_VARIANTS = 500;

    private VariantMatrixGenerator() {}

    /**
     * One attribute with its values, in display order.
     *
     * @param attributeId the attribute type
     * @param valueIds its values
     */
    public record Axis(UUID attributeId, List<UUID> valueIds) {
        public Axis {
            Objects.requireNonNull(attributeId, "attributeId");
            valueIds = List.copyOf(valueIds);
        }
    }

    /**
     * A variant that already exists.
     *
     * @param variantId the variant
     * @param valueIds its combination
     * @param active whether it is active
     */
    public record Existing(UUID variantId, Set<UUID> valueIds, boolean active) {
        public Existing {
            Objects.requireNonNull(variantId, "variantId");
            valueIds = Set.copyOf(valueIds);
        }
    }

    /**
     * What regenerating does.
     *
     * @param toCreate new combinations, each in attribute order
     * @param toDeactivate active variants whose combination is now excluded
     * @param unchanged existing variants left as they are
     */
    public record Plan(List<List<UUID>> toCreate, List<UUID> toDeactivate, int unchanged) {
        public Plan {
            toCreate = List.copyOf(toCreate);
            toDeactivate = List.copyOf(toDeactivate);
        }
    }

    /** Every combination, in attribute order (the first attribute varies slowest). */
    public static List<List<UUID>> combinations(List<Axis> axes) {
        long count = 1;
        for (Axis axis : axes) {
            count *= Math.max(axis.valueIds().size(), 1);
            if (count > MAX_VARIANTS) {
                throw new BusinessRuleViolation(
                        CatalogErrorCode.TOO_MANY_VARIANTS,
                        "A product has at most " + MAX_VARIANTS + " variants",
                        Map.of("maxVariants", MAX_VARIANTS));
            }
        }
        List<List<UUID>> combinations = new ArrayList<>();
        combinations.add(List.of());
        for (Axis axis : axes) {
            if (axis.valueIds().isEmpty()) {
                continue;
            }
            List<List<UUID>> next = new ArrayList<>();
            for (List<UUID> prefix : combinations) {
                for (UUID value : axis.valueIds()) {
                    List<UUID> combination = new ArrayList<>(prefix);
                    combination.add(value);
                    next.add(List.copyOf(combination));
                }
            }
            combinations = next;
        }
        return combinations.size() == 1 && combinations.getFirst().isEmpty() ? List.of() : combinations;
    }

    /**
     * Plans a (re)generation.
     *
     * @param excluded whether a combination falls under one of the product's exclusions
     * @param existing the product's variants
     */
    public static Plan plan(List<Axis> axes, Predicate<Collection<UUID>> excluded, List<Existing> existing) {
        List<List<UUID>> toCreate = new ArrayList<>();
        Set<UUID> toDeactivate = new HashSet<>();
        List<UUID> deactivationOrder = new ArrayList<>();
        for (List<UUID> combination : combinations(axes)) {
            Set<UUID> values = Set.copyOf(combination);
            List<Existing> matching =
                    existing.stream().filter(e -> e.valueIds().equals(values)).toList();
            if (excluded.test(combination)) {
                matching.stream()
                        .filter(Existing::active)
                        .filter(e -> toDeactivate.add(e.variantId()))
                        .forEach(e -> deactivationOrder.add(e.variantId()));
            } else if (matching.isEmpty()) {
                toCreate.add(combination);
            }
        }
        if (existing.size() + toCreate.size() > MAX_VARIANTS) {
            throw new BusinessRuleViolation(
                    CatalogErrorCode.TOO_MANY_VARIANTS,
                    "A product has at most " + MAX_VARIANTS + " variants",
                    Map.of("maxVariants", MAX_VARIANTS));
        }
        return new Plan(toCreate, deactivationOrder, existing.size() - deactivationOrder.size());
    }
}
