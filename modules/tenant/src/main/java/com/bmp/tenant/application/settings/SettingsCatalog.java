package com.bmp.tenant.application.settings;

import com.bmp.shared.error.FieldError;
import com.bmp.tenant.domain.model.SettingGroup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The closed set of settings per group, defined by JSON schemas in {@code classpath:settings/<group>.schema.json}:
 * keys, types, ranges and defaults (TEN-03). The schemas are the single source of truth for defaults.
 *
 * <p>The schema validator (networknt 2.x, shared with the contract tests) works on Jackson 2 trees, so documents
 * cross over as JSON text; the rest of the module uses Jackson 3.
 */
@Component
public class SettingsCatalog {

    private static final ObjectMapper JACKSON2 = new ObjectMapper();

    private final JsonMapper json;
    private final Map<SettingGroup, Schema> schemas = new EnumMap<>(SettingGroup.class);
    private final Map<SettingGroup, ObjectNode> defaults = new EnumMap<>(SettingGroup.class);
    private final Map<SettingGroup, Set<String>> keys = new EnumMap<>(SettingGroup.class);

    public SettingsCatalog(JsonMapper json) {
        this.json = json;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        for (SettingGroup group : SettingGroup.values()) {
            JsonNode schemaNode = read(json, "settings/" + group.code() + ".schema.json");
            schemas.put(group, registry.getSchema(toJackson2(schemaNode)));
            ObjectNode groupDefaults = json.createObjectNode();
            Set<String> groupKeys = new LinkedHashSet<>();
            schemaNode.get("properties").properties().forEach(property -> {
                groupKeys.add(property.getKey());
                groupDefaults.set(property.getKey(), property.getValue().get("default"));
            });
            defaults.put(group, groupDefaults);
            keys.put(group, Set.copyOf(groupKeys));
        }
    }

    public Set<String> keys(SettingGroup group) {
        return of(keys, group);
    }

    /** A fresh copy of the group's defaults. */
    public ObjectNode defaults(SettingGroup group) {
        return of(defaults, group).deepCopy();
    }

    /** Schema violations of a complete group document, one field error per offending key. */
    public List<FieldError> validate(SettingGroup group, ObjectNode document) {
        List<FieldError> errors = new ArrayList<>();
        for (Error error : of(schemas, group).validate(toJackson2(document))) {
            String field = fieldName(error.getInstanceLocation().toString());
            errors.add(new FieldError(
                    field.isEmpty() ? String.valueOf(error.getProperty()) : field,
                    error.getKeyword().toUpperCase(Locale.ROOT),
                    error.getMessage()));
        }
        return errors;
    }

    private com.fasterxml.jackson.databind.JsonNode toJackson2(JsonNode node) {
        try {
            return JACKSON2.readTree(json.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A Jackson 3 tree is always valid JSON", e);
        }
    }

    /** {@code /returnWindowDays} or {@code $.returnWindowDays} becomes {@code returnWindowDays}. */
    static String fieldName(String location) {
        String path = location.startsWith("$") ? location.substring(1) : location;
        path = path.startsWith(".") || path.startsWith("/") ? path.substring(1) : path;
        return path.replace('/', '.');
    }

    /** Every group is loaded at construction, so a lookup always succeeds. */
    private static <T> T of(Map<SettingGroup, T> byGroup, SettingGroup group) {
        return Objects.requireNonNull(byGroup.get(group), group.code());
    }

    private static JsonNode read(JsonMapper json, String resource) {
        try (InputStream in = SettingsCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing settings schema " + resource);
            }
            return json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
