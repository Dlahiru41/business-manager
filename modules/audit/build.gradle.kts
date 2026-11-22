plugins {
    id("bmp.spring-module")
}

description = "Hash-chained audit trail of API writes and domain events."

dependencies {
    implementation("io.micrometer:micrometer-core")
}
