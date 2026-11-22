package com.bmp.test.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code EventContractTest} check (07-events-and-messaging.md §7): an event serialized the way the outbox stores
 * it must conform to its payload schema in {@code contracts/asyncapi/events.yaml}.
 */
public final class EventContract {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode DOCUMENT = load();

    private EventContract() {}

    /**
     * Fails unless {@code eventJson} conforms to schema {@code schemaName}.
     *
     * @param eventJson the event as JSON text, written by the application's JSON mapper
     * @param schemaName the payload schema, e.g. {@code TenantCreated}
     */
    public static void assertConforms(String eventJson, String schemaName) {
        assertThat(DOCUMENT.path("components").path("schemas").has(schemaName))
                .as("events.yaml documents %s", schemaName)
                .isTrue();
        ObjectNode root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/" + schemaName);
        root.set("components", DOCUMENT.path("components"));
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(root);
        List<Error> errors;
        try {
            errors = schema.validate(JSON.readTree(eventJson));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(errors).as("%s payload %s", schemaName, eventJson).isEmpty();
    }

    private static JsonNode load() {
        Path file = Path.of(System.getProperty("bmp.contracts.dir", "contracts"), "asyncapi", "events.yaml");
        try {
            return YAML.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
