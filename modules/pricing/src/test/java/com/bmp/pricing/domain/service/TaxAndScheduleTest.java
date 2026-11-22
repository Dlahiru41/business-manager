package com.bmp.pricing.domain.service;

import static com.bmp.pricing.domain.PricingFixtures.EGG;
import static com.bmp.pricing.domain.PricingFixtures.NOW;
import static com.bmp.pricing.domain.PricingFixtures.PIECE;
import static com.bmp.pricing.domain.PricingFixtures.START;
import static com.bmp.pricing.domain.PricingFixtures.TENANT;
import static com.bmp.pricing.domain.PricingFixtures.TRAY;
import static com.bmp.pricing.domain.PricingFixtures.assertCode;
import static com.bmp.pricing.domain.PricingFixtures.rate;
import static com.bmp.pricing.domain.PricingFixtures.vat;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.pricing.domain.PricingFixtures;
import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceChangeKind;
import com.bmp.pricing.domain.model.PriceKey;
import com.bmp.pricing.domain.model.TaxRate;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.Money;
import com.bmp.shared.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TaxAndScheduleTest {

    private static final UUID LIST = UUID.randomUUID();
    private static final PriceKey EGG_PIECE = new PriceKey(EGG, PIECE, BigDecimal.ONE);
    private static final PriceKey EGG_TRAY = new PriceKey(EGG, TRAY, BigDecimal.ONE);

    private static Money lkr(String amount) {
        return Money.of(amount, CurrencyCode.LKR);
    }

    private static TaxCalculator.Split split(String amount, boolean inclusive, TaxRate... rates) {
        return TaxCalculator.split(lkr(amount), inclusive, List.of(rates), RoundingPolicy.defaultPolicy());
    }

    @Test
    void prc07ASingleInclusiveRateIsGrossTimesRateOverHundredPlusRate() {
        TaxCalculator.Split split = split("550.00", true, vat("18"));

        assertThat(split.tax()).isEqualTo(lkr("83.90"));
        assertThat(split.net()).isEqualTo(lkr("466.10"));
        assertThat(split.total()).isEqualTo(lkr("550.00"));
    }

    @Test
    void prc07ExclusiveRatesAreAddedOnTopAndCompoundRatesIncludeTheOtherTaxes() {
        TaxRate vat = vat("10");
        TaxRate levy = rate(PricingFixtures.VAT, "LEVY", "5", true, LocalDate.parse("2026-01-01"), null);

        TaxCalculator.Split exclusive = split("100.00", false, levy, vat);
        TaxCalculator.Split inclusive = split("115.50", true, levy, vat);

        assertThat(exclusive.taxes()).extracting(LineTax::code).containsExactly("VAT", "LEVY");
        assertThat(exclusive.taxes())
                .extracting(t -> t.amount().amount().toPlainString())
                .containsExactly("10.00", "5.50");
        assertThat(exclusive.total()).isEqualTo(lkr("115.50"));
        assertThat(inclusive.net()).isEqualTo(lkr("100.00"));
        assertThat(inclusive.tax()).isEqualTo(lkr("15.50"));
    }

    @Test
    void prc07TwoSimpleInclusiveRatesShareTheNetAndTheNetAbsorbsRounding() {
        TaxRate sscl = rate(PricingFixtures.VAT, "SSCL", "2.5", false, LocalDate.parse("2026-01-01"), null);

        TaxCalculator.Split split = split("100.00", true, vat("18"), sscl);

        assertThat(split.taxes())
                .extracting(t -> t.amount().amount().toPlainString())
                .containsExactly("2.07", "14.94");
        assertThat(split.net().plus(split.tax())).isEqualTo(lkr("100.00"));
        assertThat(split("0.05", true, vat("18")).tax())
                .as("0.0076 rounds to 0.01")
                .isEqualTo(lkr("0.01"));
        assertThat(split("80.00", true).taxes()).isEmpty();
        assertThat(split("80.00", false).net()).isEqualTo(lkr("80.00"));
    }

    private static List<PriceSchedule.Change> apply(List<Price> existing, PriceSchedule.Row... rows) {
        return PriceSchedule.apply(TENANT.value(), LIST, List.of(rows), existing, NOW, 2, UUID::randomUUID);
    }

    private static PriceSchedule.Row row(PriceKey key, String amount, @Nullable Instant from, @Nullable Instant to) {
        return new PriceSchedule.Row(key, new BigDecimal(amount), from, to);
    }

    private static Price existing(PriceKey key, String amount, Instant from, @Nullable Instant to) {
        return Price.create(UUID.randomUUID(), TENANT.value(), LIST, key, new BigDecimal(amount), from, to, 1);
    }

    @Test
    void prc02APriceWithoutStartEndsTheOpenPriceNowAndStartsNow() {
        Price open = existing(EGG_PIECE, "50.00", START, null);

        List<PriceSchedule.Change> changes = apply(new ArrayList<>(List.of(open)), row(EGG_PIECE, "55.00", null, null));

        assertThat(changes)
                .extracting(PriceSchedule.Change::kind)
                .containsExactly(PriceChangeKind.ENDED, PriceChangeKind.CREATED);
        assertThat(open.validTo()).isEqualTo(NOW);
        assertThat(open.listVersion()).isEqualTo(2);
        assertThat(changes.getLast().price().validFrom()).isEqualTo(NOW);
        assertThat(changes.getLast().price().amount()).isEqualByComparingTo("55.00");
        assertThat(changes.getLast().price().listVersion()).isEqualTo(2);
        assertThat(changes.getFirst().previousValidTo()).isNull();
    }

    @Test
    void prc02APriceWithTheStartOfAnExistingOneChangesIt() {
        Instant next = Instant.parse("2026-12-01T00:00:00Z");
        Price scheduled = existing(EGG_PIECE, "56.00", next, null);
        Price current = existing(EGG_PIECE, "55.00", START, next);

        List<PriceSchedule.Change> changes = apply(List.of(current, scheduled), row(EGG_PIECE, "57.00", next, null));

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(PriceChangeKind.UPDATED);
            assertThat(change.previousAmount()).isEqualByComparingTo("56.00");
            assertThat(change.price().amount()).isEqualByComparingTo("57.00");
        });
    }

    @Test
    void prc02OverlappingWindowsOfTheSameKeyAreRefused() {
        Instant next = Instant.parse("2026-12-01T00:00:00Z");
        Price scheduled = existing(EGG_PIECE, "56.00", next, null);

        assertCode(
                () -> apply(List.of(scheduled), row(EGG_PIECE, "55.00", null, null)), PricingErrorCode.PRICE_OVERLAP);
        Instant soon = Instant.parse("2026-11-25T00:00:00Z");
        assertCode(
                () -> apply(List.of(scheduled), row(EGG_PIECE, "55.00", soon, Instant.parse("2026-12-02T00:00:00Z"))),
                PricingErrorCode.PRICE_OVERLAP);
        assertThat(apply(List.of(scheduled), row(EGG_PIECE, "55.00", soon, next)))
                .as("ending where the next one starts is no overlap")
                .singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo(PriceChangeKind.CREATED));
        assertThat(apply(List.of(scheduled), row(new PriceKey(EGG, PIECE, new BigDecimal("300")), "46.00", null, null)))
                .as("another minimum quantity is another key")
                .hasSize(1);
        assertThat(apply(List.of(scheduled), row(new PriceKey(EGG, TRAY, BigDecimal.ONE), "1600.00", null, null)))
                .hasSize(1);
    }

    @Test
    void prc02RowsAreValidatedBeforeAnythingChanges() {
        assertCode(
                () -> apply(List.of(), row(EGG_PIECE, "55.00", null, null), row(EGG_PIECE, "56.00", null, null)),
                PricingErrorCode.DUPLICATE_PRICE_ROWS);
        assertCode(() -> apply(List.of(), row(EGG_PIECE, "55.00", NOW, NOW)), PricingErrorCode.INVALID_VALIDITY);
        assertCode(
                () -> apply(List.of(), row(EGG_PIECE, "55.00", null, NOW.minusSeconds(1))),
                PricingErrorCode.INVALID_VALIDITY);
    }

    @Test
    void prc02APriceThatAlreadyAppliedIsNeverRewritten() {
        Instant next = Instant.parse("2026-12-01T00:00:00Z");
        Price current = existing(EGG_PIECE, "55.00", START, null);
        Price past = existing(EGG_TRAY, "1600.00", START, Instant.parse("2026-06-01T00:00:00Z"));

        assertCode(
                () -> apply(List.of(current), row(EGG_PIECE, "50.00", START, null)), PricingErrorCode.PRICE_IN_FORCE);
        assertCode(
                () -> apply(List.of(current), row(EGG_PIECE, "55.00", START, NOW.minusSeconds(60))),
                PricingErrorCode.PRICE_IN_FORCE);
        assertCode(() -> apply(List.of(past), row(EGG_TRAY, "1600.00", START, null)), PricingErrorCode.PRICE_IN_FORCE);
        assertThat(apply(List.of(current), row(EGG_PIECE, "55.00", START, next)))
                .as("ending a price in force later changes only the future")
                .singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo(PriceChangeKind.UPDATED));
        assertThat(current.validTo()).isEqualTo(next);
        assertThat(current.amount()).isEqualByComparingTo("55.00");
    }

    @Test
    void prc02ANewPriceCannotStartInThePast() {
        assertCode(
                () -> apply(List.of(), row(EGG_PIECE, "55.00", NOW.minusSeconds(1), null)),
                PricingErrorCode.PRICE_IN_FORCE);
        assertThat(apply(List.of(), row(EGG_PIECE, "55.00", NOW, null)))
                .as("starting exactly now is allowed")
                .hasSize(1);
    }

    @Test
    void prc02AnOpenPriceThatStartedNowIsChangedNotEnded() {
        Price startedNow = existing(EGG_PIECE, "55.00", NOW, null);

        List<PriceSchedule.Change> changes = apply(List.of(startedNow), row(EGG_PIECE, "54.00", null, null));

        assertThat(changes).singleElement().satisfies(c -> assertThat(c.kind()).isEqualTo(PriceChangeKind.UPDATED));
        assertThat(startedNow.amount()).isEqualByComparingTo("54.00");
    }
}
