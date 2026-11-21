import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// Base conventions for every Java project: Java 25 toolchain, strict compiler (Error Prone + NullAway, -Werror),
// Spotless (palantir-java-format), JaCoCo coverage gates and an `integrationTest` JVM test suite.
// Quality gates here must never be disabled (CLAUDE.md §1.4).

plugins {
    `java-library`
    `java-test-fixtures`
    `jvm-test-suite`
    jacoco
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = catalog.findLibrary(alias).orElseThrow { GradleException("Missing library alias $alias") }

fun ver(alias: String): String =
    catalog.findVersion(alias).orElseThrow { GradleException("Missing version alias $alias") }.requiredVersion

val javaVersion = ver("java").toInt()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

val boms = listOf("spring-boot-bom", "spring-modulith-bom", "resilience4j-bom")

dependencies {
    for (bom in boms) {
        for (conf in listOf("implementation", "compileOnly", "annotationProcessor", "testFixturesImplementation")) {
            add(conf, platform(lib(bom)))
        }
    }
    add("api", lib("jspecify"))
    add("errorprone", lib("errorprone-core"))
    add("errorprone", lib("nullaway"))

    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testImplementation", "org.assertj:assertj-core")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    val isTestCompilation = name.contains("Test")
    options.encoding = "UTF-8"
    options.release = javaVersion
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror", "-parameters"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*[/\\\\]build[/\\\\]generated[/\\\\].*"
        option("NullAway:AnnotatedPackages", "com.bmp")
        option("NullAway:JSpecifyMode", "true")
        // Production code is null-checked; tests rely on assertions instead of nullness proofs.
        check("NullAway", if (isTestCompilation) CheckSeverity.OFF else CheckSeverity.ERROR)
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(ver("palantir-java-format"))
        forbidWildcardImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
            }
            targets.configureEach {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.getByName("testImplementation"))
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.getByName("testRuntimeOnly"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    systemProperty("user.timezone", "UTC")
    systemProperty("file.encoding", "UTF-8")
    systemProperty("bmp.contracts.dir", rootProject.layout.projectDirectory.dir("contracts").asFile.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

jacoco {
    toolVersion = ver("jacoco")
}

val coverageData = fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") }
val coverageClasses =
    the<SourceSetContainer>().named("main").map { main ->
        main.output.classesDirs.asFileTree.matching {
            exclude("**/generated/**")
            exclude("**/*MapperImpl.class")
            // The bootstrap main() runs under bootRun / in the container, never inside a test JVM.
            exclude("com/bmp/BmpApplication.class")
        }
    }

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"), tasks.named("integrationTest"))
    executionData.setFrom(coverageData)
    classDirectories.setFrom(coverageClasses)
    reports {
        xml.required = true
        html.required = true
    }
}

// Coverage gates from 09-testing-and-quality.md §5.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"), tasks.named("integrationTest"))
    executionData.setFrom(coverageData)
    classDirectories.setFrom(coverageClasses)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("com.bmp.*.domain*", "com.bmp.*.application*")
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
