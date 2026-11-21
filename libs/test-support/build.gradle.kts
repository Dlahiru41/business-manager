plugins {
    id("bmp.java-conventions")
}

description = "Shared test infrastructure: Testcontainers, JWT test tokens, problem and contract assertions, fixtures."

dependencies {
    api(project(":libs:shared-kernel"))
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-starter-webmvc-test")
    api("org.springframework.boot:spring-boot-starter-jackson")
}
