package com.bmp.finance.domain.model;

import java.util.UUID;

/** Where a journal line goes: a seeded account by its key, or a particular account (expense categories, manual). */
public sealed interface AccountRef {

    static AccountRef of(SystemKey key) {
        return new Seeded(key);
    }

    static AccountRef of(UUID accountId) {
        return new Id(accountId);
    }

    /** A seeded account, resolved per tenant when the journal is written. */
    record Seeded(SystemKey key) implements AccountRef {}

    /** A particular account of the tenant. */
    record Id(UUID accountId) implements AccountRef {}
}
