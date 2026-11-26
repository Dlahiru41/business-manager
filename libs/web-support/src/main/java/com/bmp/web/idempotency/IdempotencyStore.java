package com.bmp.web.idempotency;

import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Durable record of idempotency keys per tenant ({@code platform.idempotency_record}). {@link #begin} must be atomic:
 * of two concurrent calls with the same key exactly one returns {@link Started}.
 */
public interface IdempotencyStore {

    /** Claims the key, or reports why the request must not run. Expired records are replaced. */
    Outcome begin(TenantId tenant, String key, String requestHash, Instant now, Instant expiresAt);

    /** Stores the response of a request that ran to completion. */
    void complete(TenantId tenant, String key, StoredResponse response);

    /** Forgets a claimed key (the request failed with 5xx), so the client can retry. */
    void release(TenantId tenant, String key);

    /** Deletes records that expired before {@code now}, across tenants; returns the number deleted. */
    int purgeExpired(Instant now);

    /** Result of {@link #begin}. */
    sealed interface Outcome permits Started, Replay, KeyReused, InProgress {}

    /** The key was claimed; the request runs. */
    record Started() implements Outcome {}

    /** Same key and request completed before: replay this response. */
    record Replay(StoredResponse response) implements Outcome {}

    /** Same key used for a different request. */
    record KeyReused() implements Outcome {}

    /** Same key is being processed right now. */
    record InProgress() implements Outcome {}

    /**
     * A response kept for replay.
     *
     * @param status HTTP status
     * @param headers replayed headers (Location, ETag, Content-Type)
     * @param body JSON or text body, Base64 for a binary body (see the interceptor), or {@code null} if empty
     */
    record StoredResponse(
            int status,
            Map<String, String> headers,
            @Nullable String body) {
        public StoredResponse {
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }
    }
}
