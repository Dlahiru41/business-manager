plugins {
    id("bmp.spring-module")
}

description = "Tenants, locations, business settings and receipt templates."

dependencies {
    implementation(libs.json.schema.validator)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-cache")
}
