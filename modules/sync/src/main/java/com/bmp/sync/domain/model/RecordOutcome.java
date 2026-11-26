package com.bmp.sync.domain.model;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The result of one uploaded record (SYN-04); a rejected record carries why.
 *
 * @param problem the type, error code, title, HTTP status and detail of a rejection
 */
public record RecordOutcome(
        UploadRecordType type,
        UUID id,
        UploadStatus status,
        @Nullable Problem problem) {

    public RecordOutcome {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if ((status == UploadStatus.REJECTED) != (problem != null)) {
            throw new IllegalArgumentException("A record is rejected with a problem, and only then");
        }
    }

    /** Why a record was rejected, as Problem Details (RFC 9457) fields. */
    public record Problem(
            String type,
            String code,
            String title,
            int status,
            @Nullable String detail) {}

    public static RecordOutcome accepted(UploadRecordType type, UUID id) {
        return new RecordOutcome(type, id, UploadStatus.ACCEPTED, null);
    }

    public static RecordOutcome rejected(UploadRecordType type, UUID id, Problem problem) {
        return new RecordOutcome(type, id, UploadStatus.REJECTED, problem);
    }

    /** Rule SYN-05: what a replay answers: a record accepted before is a duplicate, a rejected one stays rejected. */
    public RecordOutcome replayed() {
        return status == UploadStatus.ACCEPTED ? new RecordOutcome(type, id, UploadStatus.DUPLICATE, null) : this;
    }

    public boolean rejected() {
        return status == UploadStatus.REJECTED;
    }
}
