package com.bmp.test.persistence;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Points a Spring test context at {@link BmpPostgres}. Use through {@link WithPostgres}. */
public class PostgresInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(BmpPostgres.springProperties()).applyTo(context.getEnvironment());
    }
}
