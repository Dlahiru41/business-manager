plugins {
    id("bmp.spring-app")
}

dependencies {
    implementation(project(":libs:web-support"))
    implementation(project(":libs:persistence-support"))
    implementation(project(":modules:tenant"))
    implementation(project(":modules:iam"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:pricing"))
    implementation(project(":modules:party"))
    implementation(project(":modules:procurement"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:audit"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.datasource.micrometer)

    testImplementation(project(":libs:test-support"))
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-docs")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
}
