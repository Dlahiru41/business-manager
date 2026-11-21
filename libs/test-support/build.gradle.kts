plugins {
    id("bmp.java-conventions")
}

description = "Shared test infrastructure: Testcontainers, JWT test tokens, problem and contract assertions, fixtures."

dependencies {
    api(project(":libs:shared-kernel"))
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-starter-webmvc-test")
    api("org.springframework.boot:spring-boot-starter-jackson")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.awaitility:awaitility")
    implementation("org.postgresql:postgresql")
}

// The same role bootstrap script as deploy/local, so tests run against the production role model.
sourceSets.main {
    resources.srcDir(rootProject.layout.projectDirectory.dir("deploy/local/postgres"))
}
