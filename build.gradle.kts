plugins {
    alias(libs.plugins.dependency.check)
}

// OWASP Dependency-Check, run explicitly (`./gradlew dependencyCheckAggregate`); CI uses Trivy as the blocking scan.
// Fails on CVSS >= 7 without a documented suppression (08-security.md §9).
dependencyCheck {
    failBuildOnCVSS = 7.0f
    suppressionFile = "config/dependency-check-suppressions.xml"
    formats = listOf("HTML", "JSON")
    nvd {
        apiKey = System.getenv("NVD_API_KEY") ?: ""
    }
}
