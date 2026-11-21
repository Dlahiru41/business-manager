package com.bmp.web.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (the implementation of a generated API method) whose requests must carry an
 * {@code Idempotency-Key}. Replays of the same key and request return the stored response; reusing a key for a
 * different request is 422 {@code IDEMPOTENCY_KEY_REUSED}; a key still in progress is 409 {@code REQUEST_IN_PROGRESS}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {}
