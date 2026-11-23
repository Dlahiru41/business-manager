plugins {
    id("bmp.spring-module")
}

description = "Purchase orders, goods receipts and supplier invoices."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:party"))
}
