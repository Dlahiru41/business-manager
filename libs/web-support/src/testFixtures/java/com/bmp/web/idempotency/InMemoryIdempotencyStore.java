package com.bmp.web.idempotency;

import com.bmp.shared.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory {@link IdempotencyStore} for tests that do not need the database. */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Outcome begin(TenantId tenant, String key, String requestHash, Instant now, Instant expiresAt) {
        boolean[] claimed = {false};
        Entry existing = entries.compute(id(tenant, key), (k, current) -> {
            if (current == null || current.expiresAt().isBefore(now)) {
                claimed[0] = true;
                return new Entry(requestHash, null, expiresAt);
            }
            return current;
        });
        if (claimed[0]) {
            return new Started();
        }
        if (!existing.requestHash().equals(requestHash)) {
            return new KeyReused();
        }
        StoredResponse response = existing.response();
        return response == null ? new InProgress() : new Replay(response);
    }

    @Override
    public void complete(TenantId tenant, String key, StoredResponse response) {
        entries.computeIfPresent(id(tenant, key), (k, e) -> new Entry(e.requestHash(), response, e.expiresAt()));
    }

    @Override
    public void release(TenantId tenant, String key) {
        entries.remove(id(tenant, key));
    }

    @Override
    public int purgeExpired(Instant now) {
        int before = entries.size();
        entries.values().removeIf(e -> e.expiresAt().isBefore(now));
        return before - entries.size();
    }

    /** Claims {@code key} without completing it, to simulate a request still in progress. */
    public void claim(TenantId tenant, String key, String requestHash, Instant expiresAt) {
        entries.put(id(tenant, key), new Entry(requestHash, null, expiresAt));
    }

    public void clear() {
        entries.clear();
    }

    private static String id(TenantId tenant, String key) {
        return tenant + "/" + key;
    }

    private record Entry(String requestHash, StoredResponse response, Instant expiresAt) {}
}
