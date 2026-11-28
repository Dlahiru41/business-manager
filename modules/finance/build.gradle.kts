plugins {
    id("bmp.spring-module")
}

description = "Double-entry ledger, expenses, receivables, payables and fiscal periods."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:party"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:procurement"))
    implementation(project(":modules:inventory"))
    implementation(project(":modules:sales"))
    implementation(project(":modules:payments"))
    implementation("io.micrometer:micrometer-core")
    integrationTestImplementation(project(":modules:iam"))
    integrationTestImplementation(project(":modules:pricing"))
}
