plugins {
    id("bmp.java-conventions")
}

description = "Stable, dependency-free building blocks: money, quantities, typed ids, tenant context, errors, events."

dependencies {
    implementation(libs.java.uuid.generator)
}
