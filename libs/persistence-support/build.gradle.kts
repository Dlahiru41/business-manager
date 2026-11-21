plugins {
    id("bmp.java-conventions")
}

description = "Persistence infrastructure: tenant RLS wiring, per-module Flyway, auditing, inbox/outbox helpers, ShedLock."

dependencies {
    api(project(":libs:shared-kernel"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.modulith:spring-modulith-starter-core")
    api("org.springframework.modulith:spring-modulith-starter-jdbc")
    api(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    api("org.postgresql:postgresql")

    testImplementation(project(":libs:test-support"))
    integrationTestImplementation(project(":libs:test-support"))
}
