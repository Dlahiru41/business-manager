package com.bmp.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PRC-07 / SAL-04: a line's amount split into net and taxes, the net absorbing the rounding. */
class TaxSplitTest {

    static final CurrencyCode LKR = CurrencyCode.of("LKR");
    static final RoundingPolicy ROUNDING = RoundingPolicy.defaultPolicy();

    static TaxSplit.Rate rate(String code, String pct, boolean compound) {
        return new TaxSplit.Rate(UUID.randomUUID(), code, new BigDecimal(pct), compound);
    }

    @Test
    void anInclusiveAmountIsBackCalculated() {
        TaxSplit.Split split =
                TaxSplit.split(Money.of("550.00", LKR), true, List.of(rate("VAT", "18", false)), ROUNDING);

        assertThat(split.tax().amount()).isEqualByComparingTo("83.90");
        assertThat(split.net().amount()).isEqualByComparingTo("466.10");
        assertThat(split.total().amount()).isEqualByComparingTo("550.00");
        assertThat(split.taxes())
                .singleElement()
                .satisfies(t -> assertThat(t.code()).isEqualTo("VAT"));
    }

    @Test
    void compoundRatesApplyToTheNetPlusEarlierTaxes() {
        List<TaxSplit.Rate> rates = List.of(rate("VAT", "10", true), rate("LEVY", "5", false));
        TaxSplit.Split exclusive = TaxSplit.split(Money.of("100.00", LKR), false, rates, ROUNDING);
        TaxSplit.Split inclusive = TaxSplit.split(Money.of("115.50", LKR), true, rates, ROUNDING);

        assertThat(exclusive.taxes())
                .extracting(t -> t.amount().amount().toPlainString())
                .containsExactly("5.00", "10.50");
        assertThat(exclusive.total().amount()).isEqualByComparingTo("115.50");
        assertThat(inclusive.net().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void withoutRatesTheAmountIsNet() {
        TaxSplit.Split split = TaxSplit.split(Money.of("12.34", LKR), true, List.of(), ROUNDING);

        assertThat(split.net()).isEqualTo(split.total());
        assertThat(split.taxes()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> rate("BAD", "-1", false));
    }
}
