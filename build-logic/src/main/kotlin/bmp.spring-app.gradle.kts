import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

// Conventions for the deployable Spring Boot application that assembles all modules (modular monolith).

plugins {
    id("bmp.java-conventions")
    id("org.springframework.boot")
    id("org.cyclonedx.bom")
}

tasks.named<BootBuildImage>("bootBuildImage") {
    imageName.set("bmp/${project.name}:${project.version}")
    environment.put("BP_JVM_VERSION", project.extensions.getByType<JavaPluginExtension>().toolchain.languageVersion.get().toString())
    // Used with --publishImage in CI (main only); credentials come from the environment, never from the build.
    docker {
        publishRegistry {
            username.set(providers.environmentVariable("REGISTRY_USERNAME").orElse(""))
            password.set(providers.environmentVariable("REGISTRY_PASSWORD").orElse(""))
        }
    }
}

// `./gradlew :app:bootRun` is for local development: use the local profile unless SPRING_PROFILES_ACTIVE says otherwise.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    if (System.getenv("SPRING_PROFILES_ACTIVE") == null) {
        systemProperty("spring.profiles.active", "local")
    }
}

// The SBOM lists what ships: the runtime classpath, not build tools such as Error Prone.
tasks.withType<org.cyclonedx.gradle.CyclonedxDirectTask>().configureEach {
    includeConfigs.set(listOf("runtimeClasspath"))
}
