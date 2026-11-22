plugins {
    id("bmp.spring-module")
}

description = "Users, roles and permissions, the caller's identity and registered devices."

dependencies {
    implementation(project(":modules:tenant"))
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.micrometer)
    implementation("io.micrometer:micrometer-core")

    integrationTestImplementation(libs.testcontainers.keycloak)
}

// The local realm is imported into the Keycloak container of KeycloakIdentityProviderIT, so the test proves the
// realm file and the adapter together.
sourceSets.named("integrationTest") {
    resources.srcDir(rootProject.layout.projectDirectory.dir("deploy/local/keycloak"))
}
