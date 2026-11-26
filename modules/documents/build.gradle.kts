plugins {
    id("bmp.spring-module")
}

description = "Receipts (ESC/POS, PDF, text), print agents and print jobs."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:sales"))
    implementation(project(":libs:receipt-renderer"))
    integrationTestImplementation(project(":modules:iam"))
    integrationTestImplementation(project(":modules:catalog"))
    integrationTestImplementation(project(":modules:pricing"))
    integrationTestImplementation(project(":modules:party"))
    integrationTestImplementation(libs.pdfbox)
}
