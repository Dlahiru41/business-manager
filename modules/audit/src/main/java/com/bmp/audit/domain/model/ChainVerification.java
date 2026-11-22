package com.bmp.audit.domain.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Checks records of one chain in sequence order: each must follow the previous one (sequence + 1, matching previous
 * hash) and carry the hash of its own fields.
 */
public final class ChainVerification {

    private AuditRecord.ChainHead expected = AuditRecord.ChainHead.EMPTY;
    private long checked;
    private @Nullable Long firstBroken;

    /** The outcome of a verification. */
    public record Result(
            boolean intact, long checked, @Nullable Long firstBrokenSequence) {}

    /** Checks the next batch of records, in sequence order; stops judging after the first broken link. */
    public void accept(List<AuditRecord> batch) {
        for (AuditRecord record : batch) {
            checked++;
            if (firstBroken != null) {
                continue;
            }
            boolean linked = record.sequence() == expected.sequence() + 1
                    && record.prevHash().equals(expected.hash());
            if (!linked || !record.computeHash().equals(record.hash())) {
                firstBroken = linked ? record.sequence() : expected.sequence() + 1;
            }
            expected = new AuditRecord.ChainHead(record.sequence(), record.hash());
        }
    }

    public Result result() {
        return new Result(firstBroken == null, checked, firstBroken);
    }
}
