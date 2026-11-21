package com.bmp.tenant.adapter.in.web;

import com.bmp.tenant.adapter.in.web.generated.model.Address;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Converts JSON documents stored as text (addresses, receipt layouts, settings) to and from API objects. */
@Component
class JsonDocuments {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper json;

    JsonDocuments(JsonMapper json) {
        this.json = json;
    }

    @Nullable
    Address toAddress(@Nullable String document) {
        return document == null ? null : json.readValue(document, Address.class);
    }

    @Nullable
    String fromAddress(@Nullable Address address) {
        return address == null ? null : json.writeValueAsString(address);
    }

    Map<String, Object> toMap(String document) {
        return json.readValue(document, MAP);
    }

    Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = json.convertValue(node, MAP);
        return map != null ? map : new LinkedHashMap<>();
    }

    @Nullable
    String fromMap(@Nullable Map<String, Object> map) {
        return map == null ? null : json.writeValueAsString(map);
    }

    Map<String, JsonNode> toNodes(Map<String, Object> values) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        values.forEach((key, value) -> nodes.put(key, json.valueToTree(value)));
        return nodes;
    }
}
