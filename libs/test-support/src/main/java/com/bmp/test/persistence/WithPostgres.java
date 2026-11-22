package com.bmp.test.persistence;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.ContextConfiguration;

/**
 * Runs a Spring test against the shared PostgreSQL 18 container, connected as {@code bmp_app} with the real
 * migrations. Combine with {@code @SpringBootTest} or {@code @ApplicationModuleTest}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ContextConfiguration(initializers = PostgresInitializer.class)
public @interface WithPostgres {}
