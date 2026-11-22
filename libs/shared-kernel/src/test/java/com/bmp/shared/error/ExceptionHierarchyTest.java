package com.bmp.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExceptionHierarchyTest {

    private enum SampleErrorCode implements ErrorCode {
        THING_NOT_FOUND(ErrorCategory.NOT_FOUND);

        private final ErrorCategory category;

        SampleErrorCode(ErrorCategory category) {
            this.category = category;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public ErrorCategory category() {
            return category;
        }

        @Override
        public String module() {
            return "sample";
        }
    }

    @Test
    void shouldDeriveMessageKeysFromCode() {
        assertThat(SampleErrorCode.THING_NOT_FOUND.titleKey()).isEqualTo("error.THING_NOT_FOUND.title");
        assertThat(SampleErrorCode.THING_NOT_FOUND.detailKey()).isEqualTo("error.THING_NOT_FOUND.detail");
        assertThat(PlatformErrorCode.VERSION_CONFLICT.module()).isEqualTo("platform");
        assertThat(PlatformErrorCode.VERSION_CONFLICT.category()).isEqualTo(ErrorCategory.PRECONDITION);
    }

    @Test
    void shouldCarryCodeAndImmutableOrderedDetails() {
        Map<String, Object> details = new HashMap<>();
        details.put("thingId", "42");
        NotFoundException e = new NotFoundException(SampleErrorCode.THING_NOT_FOUND, details);
        details.put("later", "ignored");

        assertThat(e.errorCode()).isEqualTo(SampleErrorCode.THING_NOT_FOUND);
        assertThat(e.details()).containsExactly(Map.entry("thingId", "42"));
        assertThat(e).hasMessage("THING_NOT_FOUND {thingId=42}");
        assertThatThrownBy(() -> e.details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldUseCodeAsMessageWithoutDetailsOrExplicitMessage() {
        assertThat(new DomainException(SampleErrorCode.THING_NOT_FOUND)).hasMessage("THING_NOT_FOUND");
        assertThat(new BusinessRuleViolation(SampleErrorCode.THING_NOT_FOUND, "explained", Map.of()))
                .hasMessage("explained");
        assertThat(new BusinessRuleViolation(SampleErrorCode.THING_NOT_FOUND)).hasMessage("THING_NOT_FOUND");
        assertThat(new BusinessRuleViolation(SampleErrorCode.THING_NOT_FOUND, Map.of("a", 1)))
                .hasMessage("THING_NOT_FOUND {a=1}");
    }

    @Test
    void shouldRejectNullDetailValuesAndNullCode() {
        Map<String, Object> details = new HashMap<>();
        details.put("thingId", null);

        assertThatNullPointerException()
                .isThrownBy(() -> new NotFoundException(SampleErrorCode.THING_NOT_FOUND, details));
        assertThatNullPointerException().isThrownBy(() -> new DomainException(null));
    }

    @Test
    void shouldExposeVersionConflictAndValidationDetails() {
        VersionConflict conflict = new VersionConflict(7);
        ValidationFailure failure =
                new ValidationFailure(List.of(new FieldError("lines[0].qty", "MUST_BE_POSITIVE", "must be > 0")));

        assertThat(conflict.currentVersion()).isEqualTo(7);
        assertThat(conflict.details()).containsEntry("currentVersion", 7L);
        assertThat(conflict.errorCode()).isEqualTo(PlatformErrorCode.VERSION_CONFLICT);
        assertThat(failure.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
        assertThat(failure.fieldErrors()).extracting(FieldError::field).containsExactly("lines[0].qty");
    }

    @Test
    void shouldKeepCauseOnIntegrationAndTechnicalExceptions() {
        IOException cause = new IOException("connection refused");

        assertThat(new IntegrationException(PlatformErrorCode.DEPENDENCY_UNAVAILABLE, Map.of("system", "idp"), cause))
                .hasCause(cause);
        assertThat(new TechnicalException(PlatformErrorCode.INTERNAL_ERROR, Map.of(), cause))
                .hasCause(cause);
        assertThat(new TechnicalException(PlatformErrorCode.TENANT_CONTEXT_MISSING))
                .hasNoCause();
    }

    @Test
    void fieldErrorShouldRequireAllComponents() {
        assertThatNullPointerException().isThrownBy(() -> new FieldError(null, "C", "m"));
        assertThatNullPointerException().isThrownBy(() -> new FieldError("f", null, "m"));
        assertThatNullPointerException().isThrownBy(() -> new FieldError("f", "C", null));
    }
}
