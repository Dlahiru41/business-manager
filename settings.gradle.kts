pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bmp-platform"

include(
    "libs:shared-kernel",
    "libs:web-support",
    "libs:persistence-support",
    "libs:test-support",
    "libs:receipt-renderer")

// Every directory under modules/ with a build file is a bounded-context module (Spring Modulith application module).
file("modules")
    .listFiles { dir -> dir.isDirectory && dir.resolve("build.gradle.kts").exists() }
    .orEmpty()
    .sortedBy { it.name }
    .forEach { include("modules:${it.name}") }

include("app")

// The print agent on the shop PC (phase 03): a separate application, not part of the platform.
include("print-agent")
