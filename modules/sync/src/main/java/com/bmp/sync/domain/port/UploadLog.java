package com.bmp.sync.domain.port;

import com.bmp.sync.domain.model.RecordOutcome;
import com.bmp.sync.domain.model.UploadRecordType;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Uploaded records of the current tenant by type and id (SYN-05): written once. */
public interface UploadLog {

    Optional<RecordOutcome> find(UploadRecordType type, UUID id);

    /** Whether each of {@code ids} was rejected (whatever its type); ids never uploaded are absent. */
    Map<UUID, Boolean> rejectedById(Collection<UUID> ids);

    /** Stores the outcome; {@code false} when another request stored this record first. */
    boolean record(RecordOutcome outcome, UUID deviceId);
}
