plugins {
    id("bmp.spring-module")
}

description = "Read models built from events, reports, the owner dashboard and exports."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:sales"))
    implementation(project(":modules:payments"))
    implementation(project(":modules:finance"))
    implementation(project(":modules:sync"))
    implementation("io.micrometer:micrometer-core")
}
