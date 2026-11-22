package com.bmp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs platform jobs (idempotency cleanup, event resubmission); ShedLock makes each run once across instances. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "bmp.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration {}
