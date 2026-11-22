package com.bmp.web.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Request fingerprint for idempotency: SHA-256 of method, path and the canonical JSON body (object properties
 * sorted, insignificant whitespace removed), so formatting differences do not count as a different request.
 */
public class RequestHasher {

    private final JsonMapper canonical;

    public RequestHasher(JsonMapper mapper) {
        this.canonical =
                mapper.rebuild().enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED).build();
    }

    public String hash(String method, String path, byte[] body) {
        return hash(method, path, body, "");
    }

    /**
     * Hash of a request as made by one caller: the same key, path and body sent by another caller is a different
     * request (422 {@code IDEMPOTENCY_KEY_REUSED}), so a stored response is never replayed to someone else.
     *
     * @param path the path including the query string
     * @param caller the authenticated subject, or {@code ""} when there is none
     */
    public String hash(String method, String path, byte[] body, String caller) {
        MessageDigest digest = sha256();
        digest.update((caller + "\n" + method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
        digest.update(canonicalBody(body));
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] canonicalBody(byte[] body) {
        if (body.length == 0) {
            return body;
        }
        try {
            JsonNode tree = canonical.readTree(body);
            return canonical.writeValueAsBytes(tree);
        } catch (JacksonException notJson) {
            return body;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }
}
