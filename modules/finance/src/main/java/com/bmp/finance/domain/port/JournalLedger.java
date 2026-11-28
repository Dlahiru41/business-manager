package com.bmp.finance.domain.port;

import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The journal ledger: insert and read only (FIN-04). */
public interface JournalLedger {

    /**
     * A journal to insert.
     *
     * @param accountIds the account of each line, in line order
     */
    record NewJournal(
            UUID id,
            String number,
            Period period,
            JournalEntry entry,
            List<UUID> accountIds,
            String currency,
            Instant postedAt,
            @Nullable UUID postedBy) {

        public NewJournal {
            accountIds = List.copyOf(accountIds);
        }
    }

    void append(NewJournal journal);

    Optional<PostedJournal> find(UUID journalId);

    /** The first journal posted for a source (e.g. a bill's SALE journal). */
    Optional<PostedJournal> findFirst(SourceType type, UUID sourceId);

    boolean isReversed(UUID journalId);
}
