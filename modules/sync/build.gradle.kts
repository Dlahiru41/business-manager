plugins {
    id("bmp.spring-module")
}

description = "Offline sync for tills: bootstrap, changes and ordered, idempotent uploads."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:iam"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:pricing"))
    implementation(project(":modules:party"))
    implementation(project(":modules:sales"))
    implementation(project(":modules:payments"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:documents"))
    implementation("io.micrometer:micrometer-core")
}
