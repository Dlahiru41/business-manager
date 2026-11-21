plugins {
    id("bmp.java-conventions")
}

description = "Web edge infrastructure: problem details, request ids, idempotency, ETags, pagination, JSON, security."

dependencies {
    api(project(":libs:shared-kernel"))
    api(project(":libs:persistence-support"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-core")
    implementation("ch.qos.logback:logback-classic")
    implementation(libs.resilience4j.circuitbreaker)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(project(":libs:test-support"))
    testFixturesImplementation(project(":libs:shared-kernel"))
}
