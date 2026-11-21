package com.bmp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Module boundaries (01-architecture-and-code-structure.md §1): no cycles, no access to another module's internals,
 * only the declared dependencies. Writes the module documentation to {@code build/spring-modulith-docs}.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(BmpApplication.class);

    @Test
    void verifiesModuleStructure() {
        MODULES.verify();
    }

    @Test
    void writesDocumentation() {
        new Documenter(MODULES, Documenter.Options.defaults().withOutputFolder("build/spring-modulith-docs"))
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
