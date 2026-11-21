package com.bmp.web.testapp;

import com.bmp.web.idempotency.InMemoryIdempotencyStore;
import com.bmp.web.problem.ConstraintErrorMapping;
import com.bmp.web.problem.ModuleMessages;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WebSupportTestApplication {

    @Bean
    ModuleMessages testMessages() {
        return new ModuleMessages("i18n/test/messages");
    }

    @Bean
    ConstraintErrorMapping thingNameTaken() {
        return ConstraintErrorMapping.of("uk_thing_tenant_name", TestErrorCode.THING_NAME_TAKEN);
    }

    @Bean
    InMemoryIdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}
