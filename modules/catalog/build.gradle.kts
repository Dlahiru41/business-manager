plugins {
    id("bmp.spring-module")
}

description = "Products, variants, units of measure, packaging units and transformation definitions."

dependencies {
    // Integration tests create tenants through the tenant module's API; catalog code itself needs nothing from it.
    integrationTestImplementation(project(":modules:tenant"))
}
