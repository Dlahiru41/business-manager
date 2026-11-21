plugins {
    id("bmp.java-conventions")
}

description = "Persistence infrastructure: tenant RLS wiring, per-module Flyway, auditing, inbox/outbox helpers, ShedLock."

dependencies {
    api(project(":libs:shared-kernel"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
}
