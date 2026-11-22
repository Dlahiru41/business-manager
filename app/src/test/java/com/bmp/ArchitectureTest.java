package com.bmp;

import com.bmp.test.architecture.CodingRules;
import com.bmp.test.architecture.HexagonalRules;
import com.bmp.test.architecture.SecurityRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architecture rules of 09-testing-and-quality.md §5 over the production code of every library and module the
 * application assembles. Test code, test infrastructure and code generated from the contracts are not checked.
 */
class ArchitectureTest {

    static final JavaClasses PRODUCTION_CODE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("/test-support/") && !location.contains("/generated/"))
            .importPackages("com.bmp");

    static Stream<ArchRule> rules() {
        return Stream.of(
                HexagonalRules.DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS,
                HexagonalRules.DOMAIN_IS_FRAMEWORK_FREE,
                HexagonalRules.APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS,
                HexagonalRules.CONTROLLERS_ARE_THIN,
                HexagonalRules.MODULES_USE_ONLY_OTHER_MODULES_API,
                SecurityRules.CONTROLLER_METHODS_ARE_AUTHORIZED,
                CodingRules.NO_FIELD_INJECTION,
                CodingRules.NO_STANDARD_STREAMS,
                CodingRules.NO_JAVA_UTIL_LOGGING,
                CodingRules.NO_LEGACY_DATES,
                CodingRules.NO_FLOATING_POINT_FIELDS_IN_DOMAIN,
                CodingRules.NO_FLOATING_POINT_SIGNATURES_IN_DOMAIN,
                CodingRules.SENSITIVE_TYPES_MASK_TO_STRING);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void holds(ArchRule rule) {
        rule.check(PRODUCTION_CODE);
    }
}
