package com.bmp.finance.adapter.in.web;

import com.bmp.finance.domain.port.DocumentLedger;
import com.bmp.web.pagination.CursorCodec;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Decodes the keyset cursors of the finance lists. */
final class Cursors {

    private Cursors() {}

    static @Nullable String first(@Nullable String cursor) {
        return cursor == null ? null : CursorCodec.decode(cursor, 1).getFirst();
    }

    static @Nullable UUID id(@Nullable String cursor) {
        String value = first(cursor);
        return value == null ? null : UUID.fromString(value);
    }

    static DocumentLedger.@Nullable Cursor dated(@Nullable String cursor) {
        if (cursor == null) {
            return null;
        }
        List<String> values = CursorCodec.decode(cursor, 2);
        return new DocumentLedger.Cursor(LocalDate.parse(values.get(0)), UUID.fromString(values.get(1)));
    }
}
