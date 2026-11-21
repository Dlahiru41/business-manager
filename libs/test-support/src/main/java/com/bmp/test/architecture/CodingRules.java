package com.bmp.test.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.bmp.shared.security.Sensitive;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/** Coding rules of 02-coding-standards.md and 05-logging-and-observability.md, checked on compiled code. */
public final class CodingRules {

    public static final ArchRule NO_FIELD_INJECTION = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.because(
            "constructor injection only (CLAUDE.md §1.4)");

    public static final ArchRule NO_STANDARD_STREAMS =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
                    "log through SLF4J, never System.out or printStackTrace (05 §1)");

    public static final ArchRule NO_JAVA_UTIL_LOGGING =
            GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.because("SLF4J only (05 §1)");

    public static final ArchRule NO_LEGACY_DATES = noClasses()
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(Date.class, Calendar.class, SimpleDateFormat.class)
            .because("java.time only (02-coding-standards.md)");

    /** Money and quantities are BigDecimal value objects; binary floating point never appears in a domain. */
    public static final ArchRule NO_FLOATING_POINT_FIELDS_IN_DOMAIN = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("com.bmp.*.domain..", "com.bmp.shared..")
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class)
            .because("never double/float for money or quantities (CLAUDE.md §1.4)")
            .allowEmptyShould(true);

    public static final ArchRule NO_FLOATING_POINT_SIGNATURES_IN_DOMAIN = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("com.bmp.*.domain..", "com.bmp.shared..")
            .should()
            .haveRawReturnType(double.class)
            .orShould()
            .haveRawReturnType(float.class)
            .orShould()
            .haveRawParameterTypes(double.class)
            .orShould()
            .haveRawParameterTypes(float.class)
            .because("never double/float for money or quantities (CLAUDE.md §1.4)")
            .allowEmptyShould(true);

    /**
     * {@code @Sensitive} types declare their own masking {@code toString()}, so a value logged by mistake shows no
     * personal data or credential (05 §4). ArchUnit cannot see the arguments of a log call, so this is the enforceable
     * half of "never log a sensitive type"; the masking itself is unit tested per type.
     */
    public static final ArchRule SENSITIVE_TYPES_MASK_TO_STRING = classes()
            .that()
            .areAnnotatedWith(Sensitive.class)
            .should(declareToString())
            .because("@Sensitive types mask their values in toString() (05 §4)")
            .allowEmptyShould(true);

    private CodingRules() {}

    private static ArchCondition<JavaClass> declareToString() {
        return new ArchCondition<>("declare toString()") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                boolean declared = type.getMethods().stream()
                        .anyMatch(m -> m.getName().equals("toString")
                                && m.getRawParameterTypes().isEmpty());
                if (!declared) {
                    events.add(SimpleConditionEvent.violated(type, type.getName() + " does not declare toString()"));
                }
            }
        };
    }
}
