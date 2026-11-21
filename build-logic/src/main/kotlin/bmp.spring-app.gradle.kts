import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

// Conventions for the deployable Spring Boot application that assembles all modules (modular monolith).

plugins {
    id("bmp.java-conventions")
    id("org.springframework.boot")
    id("org.cyclonedx.bom")
}

tasks.named<BootBuildImage>("bootBuildImage") {
    imageName.set("bmp/${project.name}:${project.version}")
    environment.put("BP_JVM_VERSION", the<JavaPluginExtension>().toolchain.languageVersion.get().toString())
}
