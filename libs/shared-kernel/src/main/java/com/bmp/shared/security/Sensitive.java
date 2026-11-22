package com.bmp.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type that holds personal data or a credential (05-logging-and-observability.md §4). Its {@code toString()}
 * must mask those values, and an architecture rule forbids passing it to a logger.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Sensitive {}
