plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.plugin.spotless)
    implementation(libs.plugin.errorprone)
    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.openapi.generator)
    implementation(libs.plugin.pitest)
    implementation(libs.plugin.cyclonedx)
}
