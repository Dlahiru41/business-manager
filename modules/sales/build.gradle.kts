plugins {
    id("bmp.spring-module")
}

description = "Registers, shifts, bills, voids, returns and credit notes."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:iam"))
    implementation(project(":modules:catalog"))
    implementation(project(":modules:party"))
    implementation(project(":modules:pricing"))
}
