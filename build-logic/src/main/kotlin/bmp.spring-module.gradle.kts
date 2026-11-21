import net.ltgt.gradle.errorprone.errorprone
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

// Conventions for a bounded-context module (one Spring Modulith application module rooted at com.bmp.<module>).
// Wires shared libraries, MapStruct, contract-first code generation and PIT for the module's domain package.

plugins {
    id("bmp.java-conventions")
    id("info.solidsoft.pitest")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = catalog.findLibrary(alias).orElseThrow { GradleException("Missing library alias $alias") }

fun ver(alias: String): String =
    catalog.findVersion(alias).orElseThrow { GradleException("Missing version alias $alias") }.requiredVersion

val moduleName: String = project.name

dependencies {
    add("api", project(":libs:shared-kernel"))
    add("implementation", project(":libs:web-support"))
    add("implementation", project(":libs:persistence-support"))
    add("implementation", lib("mapstruct"))
    add("implementation", "io.micrometer:micrometer-observation")
    add("annotationProcessor", lib("mapstruct-processor"))
    add("testImplementation", project(":libs:test-support"))
}

// Contract first (03-api-standards.md §1): generate controller interfaces and DTOs from contracts/openapi/<module>.yaml.
val openApiSpec = rootProject.layout.projectDirectory.file("contracts/openapi/$moduleName.yaml").asFile
if (openApiSpec.exists()) {
    apply(plugin = "org.openapi.generator")
    val generatedDir = layout.buildDirectory.dir("generated/openapi")
    val generatedPackage = "com.bmp.$moduleName.adapter.in.web.generated"

    tasks.named<GenerateTask>("openApiGenerate") {
        inputs.dir(rootProject.layout.projectDirectory.dir("contracts/openapi"))
        generatorName.set("spring")
        // A file: URI (percent-encoded) so relative $refs resolve even when the path contains spaces.
        remoteInputSpec.set(openApiSpec.toURI().toString())
        outputDir.set(generatedDir)
        apiPackage.set("$generatedPackage.api")
        modelPackage.set("$generatedPackage.model")
        generateApiTests.set(false)
        generateModelTests.set(false)
        generateApiDocumentation.set(false)
        generateModelDocumentation.set(false)
        cleanupOutput.set(true)
        globalProperties.set(mapOf("apis" to "", "models" to ""))
        configOptions.set(
            mapOf(
                "interfaceOnly" to "true",
                "useSpringBoot4" to "true",
                "useJackson3" to "true",
                "useJakartaEe" to "true",
                "useTags" to "true",
                "useBeanValidation" to "true",
                "openApiNullable" to "false",
                "skipDefaultInterface" to "true",
                "useResponseEntity" to "true",
                "documentationProvider" to "none",
                "annotationLibrary" to "none",
                "dateLibrary" to "java8",
                // Absent containers stay null, so PATCH can tell "not sent" from "sent empty".
                "containerDefaultToNull" to "true",
                "sourceFolder" to "src/main/java",
            ),
        )
    }

    // Generated code is compiled in its own source set: it is not ours to fix, so it gets neither -Werror nor
    // Error Prone. Hand-written code keeps every gate and only sees the generated classes on its classpath.
    val sourceSets = the<SourceSetContainer>()
    val openapi = sourceSets.create("openapi") {
        java.srcDir(generatedDir.map { it.dir("src/main/java") })
    }
    configurations.named(openapi.implementationConfigurationName) {
        extendsFrom(configurations.getByName("implementation"))
    }
    configurations.named(openapi.compileOnlyConfigurationName) {
        extendsFrom(configurations.getByName("compileOnly"))
    }
    tasks.named<JavaCompile>(openapi.compileJavaTaskName) {
        dependsOn("openApiGenerate")
        options.errorprone.enabled.set(false)
        options.compilerArgs.removeAll(listOf("-Werror", "-Xlint:all"))
    }
    for (name in listOf("main", "test", "integrationTest", "testFixtures")) {
        sourceSets.named(name) {
            compileClasspath += openapi.output
            runtimeClasspath += openapi.output
        }
    }
    tasks.named<Jar>("jar") { from(openapi.output) }
}

// Mutation testing on the module's domain package (phase gate from phase 02, 09-testing-and-quality.md §5).
pitest {
    pitestVersion.set(ver("pitest"))
    junit5PluginVersion.set(ver("pitest-junit5"))
    targetClasses.set(setOf("com.bmp.$moduleName.domain.*"))
    threads.set(4)
    outputFormats.set(setOf("HTML", "XML"))
    timestampedReports.set(false)
    mutationThreshold.set(70)
}
