plugins {
    id("bmp.java-conventions")
}

description = "Stable, dependency-free building blocks: money, quantities, typed ids, tenant context, errors, events."

dependencies {
    implementation(libs.java.uuid.generator)
}

// Acceptance for T00-02: 100% branch coverage on the money and quantity value objects and on allocation.
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf("com.bmp.shared.money.Money", "com.bmp.shared.money.Allocation", "com.bmp.shared.quantity.Quantity")
            limit {
                counter = "BRANCH"
                minimum = "1.00".toBigDecimal()
            }
            limit {
                counter = "LINE"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}
