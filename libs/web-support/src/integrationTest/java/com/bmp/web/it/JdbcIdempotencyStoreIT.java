package com.bmp.web.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.TenantId;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.web.idempotency.IdempotencyCleanupJob;
import com.bmp.web.idempotency.IdempotencyStore;
import com.bmp.web.idempotency.IdempotencyStore.InProgress;
import com.bmp.web.idempotency.IdempotencyStore.KeyReused;
import com.bmp.web.idempotency.IdempotencyStore.Replay;
import com.bmp.web.idempotency.IdempotencyStore.Started;
import com.bmp.web.idempotency.IdempotencyStore.StoredResponse;
import com.bmp.web.idempotency.JdbcIdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = WebStoreTestApplication.class)
@WithPostgres
class JdbcIdempotencyStoreIT {

    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");
    private static final Instant TOMORROW = NOW.plus(Duration.ofHours(24));

    @Autowired
    IdempotencyStore store;

    @Autowired
    IdempotencyCleanupJob cleanup;

    private final TenantId tenant = TenantId.of(UUID.randomUUID());

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void shouldBeTheDefaultStoreWhenADatabaseIsPresent() {
        assertThat(store).isInstanceOf(JdbcIdempotencyStore.class);
    }

    @Test
    void shouldClaimThenReportInProgressThenReplay() {
        String key = key();
        StoredResponse response =
                new StoredResponse(201, Map.of("Location", "/api/v1/things/1", "ETag", "W/\"0\""), "{\"id\":\"1\"}");

        assertThat(store.begin(tenant, key, "hash-1", NOW, TOMORROW)).isInstanceOf(Started.class);
        assertThat(store.begin(tenant, key, "hash-1", NOW, TOMORROW)).isInstanceOf(InProgress.class);
        store.complete(tenant, key, response);

        var replay = store.begin(tenant, key, "hash-1", NOW, TOMORROW);
        assertThat(replay).isInstanceOfSatisfying(Replay.class, r -> {
            assertThat(r.response().status()).isEqualTo(201);
            assertThat(r.response().headers()).containsEntry("Location", "/api/v1/things/1");
            assertThat(r.response().body()).isEqualTo("{\"id\":\"1\"}");
        });
        assertThat(store.begin(tenant, key, "hash-2", NOW, TOMORROW)).isInstanceOf(KeyReused.class);
    }

    @Test
    void shouldStoreEmptyBodies() {
        String key = key();
        store.begin(tenant, key, "h", NOW, TOMORROW);
        store.complete(tenant, key, new StoredResponse(204, Map.of(), null));

        assertThat(store.begin(tenant, key, "h", NOW, TOMORROW))
                .isInstanceOfSatisfying(
                        Replay.class, r -> assertThat(r.response().body()).isNull());
    }

    @Test
    void shouldForgetReleasedKeys() {
        String key = key();
        store.begin(tenant, key, "h", NOW, TOMORROW);
        store.release(tenant, key);

        assertThat(store.begin(tenant, key, "h", NOW, TOMORROW)).isInstanceOf(Started.class);
    }

    @Test
    void shouldReplaceExpiredRecords() {
        String key = key();
        store.begin(tenant, key, "old", NOW, NOW.plusSeconds(1));
        store.complete(tenant, key, new StoredResponse(201, Map.of(), "{}"));

        assertThat(store.begin(tenant, key, "new", NOW.plusSeconds(5), TOMORROW))
                .isInstanceOf(Started.class);
    }

    @Test
    void shouldScopeKeysPerTenant() {
        String key = key();
        store.begin(tenant, key, "h", NOW, TOMORROW);

        assertThat(store.begin(TenantId.of(UUID.randomUUID()), key, "other", NOW, TOMORROW))
                .isInstanceOf(Started.class);
    }

    @Test
    void shouldPurgeExpiredRecordsOfEveryTenant() {
        TenantId other = TenantId.of(UUID.randomUUID());
        String expiredA = key();
        String expiredB = key();
        String live = key();
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        store.begin(tenant, expiredA, "h", longAgo, longAgo.plusSeconds(1));
        store.begin(other, expiredB, "h", longAgo, longAgo.plusSeconds(1));
        store.begin(tenant, live, "h", NOW, Instant.parse("2999-01-01T00:00:00Z"));

        cleanup.purgeExpired();

        assertThat(store.begin(tenant, expiredA, "different", NOW, TOMORROW)).isInstanceOf(Started.class);
        assertThat(store.begin(other, expiredB, "different", NOW, TOMORROW)).isInstanceOf(Started.class);
        assertThat(store.begin(tenant, live, "different", NOW, TOMORROW)).isInstanceOf(KeyReused.class);
    }
}
