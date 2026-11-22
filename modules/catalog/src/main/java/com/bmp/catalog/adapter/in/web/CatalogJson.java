package com.bmp.catalog.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Converts the free product properties between the API map and the stored JSON object. */
@Component
class CatalogJson {

    private static final TypeReference<LinkedHashMap<String, String>> PROPERTIES = new TypeReference<>() {};

    private final JsonMapper json;

    CatalogJson(JsonMapper json) {
        this.json = json;
    }

    Map<String, String> properties(String propertiesJson) {
        return json.readValue(propertiesJson, PROPERTIES);
    }

    String propertiesJson(@Nullable Map<String, String> properties) {
        return json.writeValueAsString(properties != null ? properties : Map.of());
    }
}
