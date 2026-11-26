package com.bmp.sales.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** How part of a bill is settled (SAL-06, SAL-07). */
public sealed interface Tender {

    UUID id();

    BigDecimal amount();

    TenderMethod method();

    /** The reference a card slip, transfer or cheque carries; never a card number (PAY-05). */
    default @Nullable String reference() {
        return null;
    }

    /** Cash handed over; {@code tendered ≥ amount}, the difference is change. */
    record Cash(UUID id, BigDecimal amount, BigDecimal tendered) implements Tender {
        public Cash {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public TenderMethod method() {
            return TenderMethod.CASH;
        }

        public BigDecimal change() {
            return tendered.subtract(amount);
        }
    }

    /** CARD, BANK_TRANSFER, MOBILE_WALLET or CHEQUE, with an optional reference. */
    record NonCash(
            UUID id,
            TenderMethod method,
            BigDecimal amount,
            @Nullable String reference) implements Tender {
        public NonCash {
            Objects.requireNonNull(id, "id");
            if (method == TenderMethod.CASH || method == TenderMethod.CREDIT) {
                throw new IllegalArgumentException("Not a non-cash payment: " + method);
            }
        }
    }

    /** Owed by the customer (SAL-07): not a payment. */
    record Credit(UUID id, BigDecimal amount) implements Tender {
        public Credit {
            Objects.requireNonNull(id, "id");
        }

        @Override
        public TenderMethod method() {
            return TenderMethod.CREDIT;
        }
    }

    static Tender of(
            UUID id,
            TenderMethod method,
            BigDecimal amount,
            @Nullable BigDecimal tendered,
            @Nullable String reference) {
        return switch (method) {
            case CASH -> new Cash(id, amount, tendered != null ? tendered : amount);
            case CREDIT -> new Credit(id, amount);
            case CARD, BANK_TRANSFER, MOBILE_WALLET, CHEQUE -> new NonCash(id, method, amount, reference);
        };
    }
}
