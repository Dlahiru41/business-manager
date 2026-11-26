plugins {
    id("bmp.spring-module")
}

description = "Stock ledger, balances, costing, transformations, counts and reorder alerts."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:procurement"))
    implementation(project(":modules:sales"))
    implementation("io.micrometer:micrometer-core")
}
