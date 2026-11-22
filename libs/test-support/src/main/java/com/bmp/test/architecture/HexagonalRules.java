package com.bmp.test.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module structure rules (01-architecture-and-code-structure.md §3): {@code adapter -> application -> domain}, a pure
 * domain, thin controllers, and other modules reached only through their {@code api} package.
 */
public final class HexagonalRules {

    /** The bounded contexts of CLAUDE.md §3; code in other {@code com.bmp} packages (libraries, app) is not a module. */
    public static final Set<String> MODULES = Set.of(
            "iam",
            "tenant",
            "catalog",
            "pricing",
            "inventory",
            "procurement",
            "party",
            "sales",
            "payments",
            "finance",
            "reporting",
            "documents",
            "sync",
            "onboarding",
            "audit",
            "notification");

    private static final Pattern MODULE_PACKAGE = Pattern.compile("com\\.bmp\\.([a-z]+)(\\..*)?");

    public static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that()
            .resideInAPackage("com.bmp.*.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.bmp.*.application..", "com.bmp.*.adapter..", "com.bmp.*.config..")
            .because("the domain is the inner layer (01 §3)")
            .allowEmptyShould(true);

    /** The domain uses only the JDK, the shared kernel, JSpecify and JPA/Hibernate mapping annotations (01 §5). */
    public static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses()
            .that()
            .resideInAPackage("com.bmp.*.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "com.bmp.web..",
                    "com.bmp.persistence..",
                    "tools.jackson..",
                    "com.fasterxml.jackson..")
            .because("domain code depends on nothing but the shared kernel and the JDK (01 §3)")
            .allowEmptyShould(true);

    public static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS = noClasses()
            .that()
            .resideInAPackage("com.bmp.*.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.bmp.*.adapter..")
            .because("dependencies point inwards: adapter -> application -> domain (01 §3)")
            .allowEmptyShould(true);

    public static final ArchRule CONTROLLERS_ARE_THIN = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.bmp.*.domain.port..", "com.bmp.*.adapter.out..")
            .because("controllers map and delegate to one handler; they never use repositories (01 §3)")
            .allowEmptyShould(true);

    public static final ArchRule MODULES_USE_ONLY_OTHER_MODULES_API = classes()
            .that()
            .resideInAPackage("com.bmp..")
            .should(onlyUseApiOfOtherModules())
            .because("cross-module access goes through the other module's api package or its events (CLAUDE.md §1.4)")
            .allowEmptyShould(true);

    private HexagonalRules() {}

    private static ArchCondition<JavaClass> onlyUseApiOfOtherModules() {
        return new ArchCondition<>("only use the api package of other modules") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> from = moduleOf(origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    Optional<String> to = moduleOf(target.getPackageName());
                    if (to.isEmpty() || to.equals(from)) {
                        continue;
                    }
                    String api = "com.bmp." + to.get() + ".api";
                    String targetPackage = target.getPackageName();
                    if (!targetPackage.equals(api) && !targetPackage.startsWith(api + ".")) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static Optional<String> moduleOf(String packageName) {
        Matcher matcher = MODULE_PACKAGE.matcher(packageName);
        return matcher.matches() && MODULES.contains(matcher.group(1))
                ? Optional.of(matcher.group(1))
                : Optional.empty();
    }
}
