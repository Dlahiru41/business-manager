plugins {
    id("bmp.spring-module")
}

description = "Payments, refunds, cash drawers and daily totals by method."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:sales"))
    implementation(project(":modules:party"))
    implementation("io.micrometer:micrometer-core")
    // The test application boots sales with what it reads, and stands in for iam's approver lookup.
    integrationTestImplementation(project(":modules:iam"))
    integrationTestImplementation(project(":modules:catalog"))
    integrationTestImplementation(project(":modules:pricing"))
}
