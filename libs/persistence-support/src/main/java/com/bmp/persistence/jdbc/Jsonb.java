package com.bmp.persistence.jdbc;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PGobject;

/** Parameter helpers for {@code JdbcClient}: {@code jsonb} values and UTC timestamps. */
public final class Jsonb {

    private Jsonb() {}

    /** A PostgreSQL JSONB parameter from a JSON document, or SQL NULL. */
    public static @Nullable PGobject of(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            PGobject value = new PGobject();
            value.setType("jsonb");
            value.setValue(json);
            return value;
        } catch (SQLException e) {
            throw new IllegalArgumentException("Invalid jsonb parameter", e);
        }
    }

    /** A {@code timestamptz} parameter in UTC. */
    public static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Reads a timestamp-with-time-zone column. */
    public static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
