package com.bmp.persistence.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Platform defaults every application and module test needs, added with the lowest precedence so configuration can
 * still override them: no open-session-in-view (06-persistence-and-data.md §5), the Modulith event registry in the
 * {@code platform} schema (created by our migration, not by Modulith), explicit module detection and schema
 * validation against the migrations.
 */
public class PersistenceDefaults implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "bmpPersistenceDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.jpa.open-in-view", "false");
        defaults.put("spring.jpa.hibernate.ddl-auto", "validate");
        defaults.put("spring.modulith.events.jdbc.schema", "platform");
        defaults.put("spring.modulith.events.jdbc.schema-initialization.enabled", "false");
        defaults.put("spring.modulith.events.republish-outstanding-events-on-restart", "true");
        defaults.put("spring.modulith.detection-strategy", "explicitly-annotated");
        // Publications stuck in a state (e.g. after a crash) are marked failed so the resubmission job retries them.
        defaults.put("spring.modulith.events.staleness.published", "PT5M");
        defaults.put("spring.modulith.events.staleness.processing", "PT5M");
        defaults.put("spring.modulith.events.staleness.resubmitted", "PT5M");
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }
}
