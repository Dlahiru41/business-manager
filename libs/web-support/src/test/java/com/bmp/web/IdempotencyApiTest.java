package com.bmp.web;

import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.web.idempotency.InMemoryIdempotencyStore;
import com.bmp.web.idempotency.RequestHasher;
import com.bmp.web.testapp.DummyController;
import com.bmp.web.testapp.WebSupportTestApplication;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = WebSupportTestApplication.class)
@AutoConfigureMockMvc
class IdempotencyApiTest {

    private static final String BODY =
            "{\"name\": \"Eggs\", \"price\": {\"amount\": \"62.50\", \"currency\": \"LKR\"}}";
    private static final String SAME_BODY_REORDERED =
            "{ \"price\": {\"currency\": \"LKR\", \"amount\": \"62.50\"},   \"name\": \"Eggs\" }";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    InMemoryIdempotencyStore store;

    @Autowired
    JsonMapper jsonMapper;

    @BeforeEach
    void reset() {
        store.clear();
        DummyController.CREATIONS.set(0);
        DummyController.EXPLOSIONS.set(0);
    }

    private MvcTestResult create(String key, String body) {
        return mvc.post()
                .uri("/test/things")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    @Test
    void shouldReplayStoredResponseWithoutRunningTheHandlerAgain() {
        String key = UUID.randomUUID().toString();

        MvcTestResult first = create(key, BODY);
        MvcTestResult replay = create(key, SAME_BODY_REORDERED);

        assertThat(first).hasStatus(201).doesNotContainHeader("Idempotent-Replayed");
        assertThat(replay).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        assertThat(replay.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(replay.getResponse().getHeader("ETag"))
                .isEqualTo(first.getResponse().getHeader("ETag"));
        assertThat(replay.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(DummyController.CREATIONS).hasValue(1);
    }

    @Test
    void shouldRejectKeyReusedForDifferentRequestWith422() {
        String key = UUID.randomUUID().toString();
        create(key, BODY);

        MvcTestResult reused = create(key, BODY.replace("Eggs", "Oil"));

        assertThatProblem(reused).hasStatus(422).hasCode("IDEMPOTENCY_KEY_REUSED");
        assertThat(DummyController.CREATIONS).hasValue(1);
    }

    @Test
    void shouldRejectKeyStillInProgressWith409AndRetryAfter() {
        String key = UUID.randomUUID().toString();
        String hash = new RequestHasher(jsonMapper).hash("POST", "/test/things", BODY.getBytes(StandardCharsets.UTF_8));
        store.claim(
                WebSupportTestApplication.DEFAULT_TENANT,
                key,
                hash,
                Instant.now().plusSeconds(60));

        MvcTestResult result = create(key, BODY);

        assertThatProblem(result).hasStatus(409).hasCode("REQUEST_IN_PROGRESS");
        assertThat(result).hasHeader("Retry-After", "1");
        assertThat(DummyController.CREATIONS).hasValue(0);
    }

    @Test
    void shouldRequireKeyAsUuid() {
        MvcTestResult missing = mvc.post()
                .uri("/test/things")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .exchange();
        MvcTestResult malformed = create("not-a-uuid", BODY);

        assertThatProblem(missing).hasStatus(400).hasCode("IDEMPOTENCY_KEY_REQUIRED");
        assertThatProblem(malformed).hasStatus(400).hasCode("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(DummyController.CREATIONS).hasValue(0);
    }

    @Test
    void shouldReleaseKeyAfterServerErrorSoClientCanRetry() {
        String key = UUID.randomUUID().toString();

        MvcTestResult first =
                mvc.post().uri("/test/explode").header("Idempotency-Key", key).exchange();
        MvcTestResult retry =
                mvc.post().uri("/test/explode").header("Idempotency-Key", key).exchange();

        assertThatProblem(first).hasStatus(500);
        assertThatProblem(retry).hasStatus(500);
        assertThat(DummyController.EXPLOSIONS).hasValue(2);
    }

    @Test
    void shouldStoreClientErrorsForReplay() {
        String key = UUID.randomUUID().toString();
        String invalid = "{\"name\": \"\", \"price\": {\"amount\": \"1\", \"currency\": \"LKR\"}}";

        MvcTestResult first = create(key, invalid);
        MvcTestResult replay = create(key, invalid);

        assertThatProblem(first).hasStatus(422);
        assertThat(replay).hasStatus(422).hasHeader("Idempotent-Replayed", "true");
    }

    @Test
    void shouldIsolateKeysPerTenantScope() {
        String key = UUID.randomUUID().toString();
        store.claim(
                com.bmp.shared.id.TenantId.of(UUID.randomUUID()),
                key,
                "other-tenant",
                Instant.now().plusSeconds(60));

        assertThat(create(key, BODY)).hasStatus(201);
    }
}
