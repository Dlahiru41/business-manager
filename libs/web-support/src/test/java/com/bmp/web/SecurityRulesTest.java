package com.bmp.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.architecture.SecurityRules;
import com.bmp.web.testapp.DummyController;
import com.bmp.web.testapp.SecuredController;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class SecurityRulesTest {

    @Test
    void shouldAcceptControllersWhosePublicMethodsDeclareAPermission() {
        var classes = new ClassFileImporter().importClasses(SecuredController.class);

        assertThat(SecurityRules.CONTROLLER_METHODS_ARE_AUTHORIZED
                        .evaluate(classes)
                        .hasViolation())
                .isFalse();
    }

    @Test
    void shouldReportPublicControllerMethodsWithoutPreAuthorize() {
        var classes = new ClassFileImporter().importClasses(DummyController.class);
        var result = SecurityRules.CONTROLLER_METHODS_ARE_AUTHORIZED.evaluate(classes);

        assertThat(result.hasViolation()).isFalse().as("DummyController methods are package-private");
        var publicOnes = new ClassFileImporter().importClasses(UnsecuredController.class);
        assertThat(SecurityRules.CONTROLLER_METHODS_ARE_AUTHORIZED
                        .evaluate(publicOnes)
                        .hasViolation())
                .isTrue();
    }

    @org.springframework.web.bind.annotation.RestController
    static class UnsecuredController {
        public String open() {
            return "no permission declared";
        }
    }
}
