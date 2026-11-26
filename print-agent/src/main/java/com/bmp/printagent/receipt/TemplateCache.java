package com.bmp.printagent.receipt;

import com.bmp.printagent.config.AgentProperties;
import com.bmp.receipt.ReceiptTemplate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The latest template from the cloud, kept on disk so the agent prints offline (DOC-06). Until one has been fetched
 * the shop name and paper width of {@code agent.yaml} head the receipt.
 */
@Component
public class TemplateCache {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AgentProperties settings;
    private final Clock clock;

    public TemplateCache(JdbcClient jdbc, JsonMapper json, AgentProperties settings, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.settings = settings;
        this.clock = clock;
    }

    /** The template receipts are printed with now. */
    public ReceiptTemplate current() {
        return cached().map(ReceiptJson::template)
                .orElseGet(() -> new ReceiptTemplate(
                        0L,
                        settings.shopName(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        settings.paperWidthMm(),
                        false,
                        true,
                        "en",
                        ReceiptJson.PRINT_ZONE));
    }

    /** The version of the cached template; empty before the cloud was reached once. */
    public Optional<Long> version() {
        return cached().map(t -> t.path("version").asLong());
    }

    /** Keeps a template the cloud sent; one that cannot be read is refused and the cached one stays. */
    public void store(JsonNode template) {
        ReceiptJson.template(template);
        jdbc.sql("""
                        MERGE INTO cached_template (id, version, body, fetched_at) KEY (id)
                        VALUES (1, :version, :body, :at)""")
                .param("version", template.path("version").asLong())
                .param("body", json.writeValueAsString(template))
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private Optional<JsonNode> cached() {
        return jdbc.sql("SELECT body FROM cached_template WHERE id = 1")
                .query(String.class)
                .optional()
                .map(json::readTree);
    }
}
