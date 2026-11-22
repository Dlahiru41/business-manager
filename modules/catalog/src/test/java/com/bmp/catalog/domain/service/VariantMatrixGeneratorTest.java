package com.bmp.catalog.domain.service;

import static com.bmp.catalog.domain.CatalogFixtures.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.Exclusion;
import com.bmp.catalog.domain.service.VariantMatrixGenerator.Axis;
import com.bmp.catalog.domain.service.VariantMatrixGenerator.Existing;
import com.bmp.catalog.domain.service.VariantMatrixGenerator.Plan;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VariantMatrixGeneratorTest {

    private static final UUID RED = UUID.randomUUID();
    private static final UUID WHITE = UUID.randomUUID();
    private static final UUID GOLDEN = UUID.randomUUID();
    private static final UUID XS = UUID.randomUUID();
    private static final UUID S = UUID.randomUUID();
    private static final UUID M = UUID.randomUUID();
    private static final UUID L = UUID.randomUUID();
    private static final UUID XL = UUID.randomUUID();

    private static final Axis COLOUR = new Axis(UUID.randomUUID(), List.of(RED, WHITE, GOLDEN));
    private static final Axis SIZE = new Axis(UUID.randomUUID(), List.of(XS, S, M, L, XL));

    private static final List<Exclusion> GOLDEN_SMALL = List.of(
            Exclusion.of(List.of(GOLDEN, XS)), Exclusion.of(List.of(GOLDEN, S)), Exclusion.of(List.of(GOLDEN, M)));

    private static Predicate<Collection<UUID>> excludedBy(List<Exclusion> exclusions) {
        return combination -> exclusions.stream().anyMatch(e -> e.excludes(combination));
    }

    @Test
    void cat05EggsAreThreeColoursTimesFiveSizesMinusGoldenYellowSmallSizes() {
        Plan plan = VariantMatrixGenerator.plan(List.of(COLOUR, SIZE), excludedBy(GOLDEN_SMALL), List.of());

        assertThat(plan.toCreate()).hasSize(12);
        assertThat(plan.toCreate()).doesNotContain(List.of(GOLDEN, XS), List.of(GOLDEN, S), List.of(GOLDEN, M));
        assertThat(plan.toCreate()).contains(List.of(GOLDEN, L), List.of(GOLDEN, XL), List.of(RED, XS));
        assertThat(plan.toCreate().getFirst())
                .as("first attribute varies slowest")
                .containsExactly(RED, XS);
        assertThat(plan.toDeactivate()).isEmpty();
        assertThat(plan.unchanged()).isZero();
    }

    @Test
    void cat05RegenerationAddsOnlyMissingCombinations() {
        List<Existing> existing =
                existingFor(VariantMatrixGenerator.plan(List.of(COLOUR, SIZE), excludedBy(GOLDEN_SMALL), List.of())
                        .toCreate());
        UUID xxl = UUID.randomUUID();
        Axis bigger = new Axis(SIZE.attributeId(), List.of(XS, S, M, L, XL, xxl));

        Plan plan = VariantMatrixGenerator.plan(List.of(COLOUR, bigger), excludedBy(GOLDEN_SMALL), existing);

        assertThat(plan.toCreate()).containsExactly(List.of(RED, xxl), List.of(WHITE, xxl), List.of(GOLDEN, xxl));
        assertThat(plan.unchanged()).isEqualTo(12);
    }

    @Test
    void cat05RegenerationDeactivatesNewlyExcludedCombinationsAndNeverDeletes() {
        List<Existing> existing =
                existingFor(VariantMatrixGenerator.plan(List.of(COLOUR, SIZE), excludedBy(GOLDEN_SMALL), List.of())
                        .toCreate());
        List<Exclusion> more = new ArrayList<>(GOLDEN_SMALL);
        more.add(Exclusion.of(List.of(WHITE, XL)));
        UUID whiteXl = existing.stream()
                .filter(e -> e.valueIds().equals(Set.of(WHITE, XL)))
                .findFirst()
                .orElseThrow()
                .variantId();

        Plan plan = VariantMatrixGenerator.plan(List.of(COLOUR, SIZE), excludedBy(more), existing);

        assertThat(plan.toDeactivate()).containsExactly(whiteXl);
        assertThat(plan.toCreate()).isEmpty();
        assertThat(plan.unchanged()).isEqualTo(11);
    }

    @Test
    void cat05AnExcludedVariantThatIsAlreadyInactiveIsLeftAlone() {
        Existing inactive = new Existing(UUID.randomUUID(), Set.of(GOLDEN, XS), false);

        Plan plan = VariantMatrixGenerator.plan(List.of(COLOUR, SIZE), excludedBy(GOLDEN_SMALL), List.of(inactive));

        assertThat(plan.toDeactivate()).isEmpty();
        assertThat(plan.toCreate()).hasSize(12);
        assertThat(plan.unchanged()).isEqualTo(1);
    }

    @Test
    void cat05AnExclusionOfOneValueRemovesEveryCombinationWithIt() {
        Plan plan = VariantMatrixGenerator.plan(
                List.of(COLOUR, SIZE), excludedBy(List.of(Exclusion.of(List.of(GOLDEN)))), List.of());

        assertThat(plan.toCreate()).hasSize(10).noneMatch(c -> c.contains(GOLDEN));
    }

    @Test
    void shouldGenerateNothingWithoutAttributes() {
        assertThat(VariantMatrixGenerator.combinations(List.of())).isEmpty();
        assertThat(VariantMatrixGenerator.combinations(List.of(new Axis(UUID.randomUUID(), List.of()))))
                .isEmpty();
    }

    @Test
    void shouldRefuseMoreThanFiveHundredVariants() {
        Axis many = new Axis(
                UUID.randomUUID(),
                IntStream.range(0, 26).mapToObj(i -> UUID.randomUUID()).toList());
        Axis twenty = new Axis(
                UUID.randomUUID(),
                IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList());

        assertCode(
                () -> VariantMatrixGenerator.combinations(List.of(many, twenty)), CatalogErrorCode.TOO_MANY_VARIANTS);
        assertThat(VariantMatrixGenerator.combinations(List.of(twenty, new Axis(UUID.randomUUID(), List.of(RED)))))
                .hasSize(20);
    }

    @Test
    void shouldRefuseRegenerationBeyondFiveHundredVariants() {
        Axis twenty = new Axis(
                UUID.randomUUID(),
                IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList());
        List<Existing> existing = IntStream.range(0, 490)
                .mapToObj(i -> new Existing(UUID.randomUUID(), Set.of(UUID.randomUUID()), true))
                .toList();

        assertCode(
                () -> VariantMatrixGenerator.plan(List.of(twenty), c -> false, existing),
                CatalogErrorCode.TOO_MANY_VARIANTS);
    }

    private static List<Existing> existingFor(List<List<UUID>> combinations) {
        return combinations.stream()
                .map(c -> new Existing(UUID.randomUUID(), Set.copyOf(c), true))
                .toList();
    }
}
