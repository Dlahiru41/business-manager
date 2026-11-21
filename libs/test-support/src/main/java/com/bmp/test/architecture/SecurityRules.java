package com.bmp.test.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Security rules (08-security.md §3). */
public final class SecurityRules {

    /** Every public controller method declares its permission, or is explicitly public. */
    public static final ArchRule CONTROLLER_METHODS_ARE_AUTHORIZED = methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .and()
            .arePublic()
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .and()
            .doNotHaveModifier(JavaModifier.BRIDGE)
            .should()
            .beAnnotatedWith(PreAuthorize.class)
            .orShould()
            .beAnnotatedWith(PermitAll.class)
            .because("every endpoint declares its permission (08-security.md §3)")
            .allowEmptyShould(true);

    private SecurityRules() {}
}
