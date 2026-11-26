import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("bmp.spring-app")
}

description =
    "Print agent on the shop PC: prints receipts on the local printer, keeps them while the printer or the cloud is " +
        "away, and takes reprints from the cloud (phase 03)."

dependencies {
    implementation(project(":libs:receipt-renderer"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation(libs.jserialcomm)
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.awaitility:awaitility")
}

// The agent's own name and the installers' version (jpackage takes digits and dots only).
val agentName = "bmp-print-agent"
val installerVersion = project.version.toString().substringBefore("-")

/**
 * `./gradlew :print-agent:jpackage -PinstallerType=exe` builds the installer of the platform it runs on: `exe` or
 * `msi` on Windows (WiX Toolset on the PATH), `deb` or `rpm` on Linux, `app-image` (default) anywhere. CI builds the
 * Windows installer (ci.yml, job print-agent-installer).
 */
tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds the print agent installer with jpackage (-PinstallerType=exe|msi|deb|rpm|app-image)."
    val bootJar = tasks.named<BootJar>("bootJar")
    dependsOn(bootJar)
    val type = providers.gradleProperty("installerType").orElse("app-image")
    val input = layout.buildDirectory.dir("jpackage/input")
    val output = layout.buildDirectory.dir("jpackage/out")
    // jpackage of the toolchain the agent is built with (bmp.java-conventions).
    val launcher = javaToolchains.launcherFor(java.toolchain)
    inputs.files(bootJar)
    inputs.property("type", type)
    outputs.dir(output)
    doFirst {
        delete(input, output)
        copy {
            from(bootJar)
            into(input)
        }
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val home = launcher.get().metadata.installationPath
        executable = home.file(if (windows) "bin/jpackage.exe" else "bin/jpackage").asFile.absolutePath
        args(
            "--type", type.get(),
            "--name", agentName,
            "--app-version", installerVersion,
            "--vendor", "BMP",
            "--description", "Prints receipts for the BMP point of sale",
            "--input", input.get().asFile.absolutePath,
            "--main-jar", bootJar.get().archiveFileName.get(),
            "--dest", output.get().asFile.absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
        )
        when (type.get()) {
            "exe", "msi" -> args("--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install")
            "deb", "rpm" -> args("--linux-shortcut")
        }
    }
}
