plugins {
    id("bmp.spring-module")
}

description = "Customers and suppliers: parties, customer groups and credit terms."

dependencies {
    implementation(project(":modules:tenant"))
}
