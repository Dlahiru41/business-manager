package com.bmp.finance.domain.posting;

import com.bmp.finance.domain.model.JournalEntry;
import java.util.Optional;

/**
 * Rule FIN-02: how one kind of source event becomes a journal (posting-rules.md §2). Rules are pure: they see only
 * finance-owned facts mapped from the event and return a balanced entry, or nothing when every amount is zero.
 *
 * @param <T> the facts of the event
 */
public interface PostingRule<T> {

    Optional<JournalEntry> post(T facts);
}
