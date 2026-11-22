package com.bmp.audit.testapp;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stand-in API of another module, to exercise the audit interceptor. */
@RestController
class ThingsController {

    static final UUID CREATED = UUID.fromString("0193a6f1-0000-7000-8000-00000000c0de");

    @PostMapping("/api/v1/things")
    @PreAuthorize("hasPermission(null, 'things:write')")
    ResponseEntity<Void> create() {
        return ResponseEntity.created(URI.create("/api/v1/things/" + CREATED)).build();
    }

    @PostMapping("/api/v1/things/{thingId}/archive")
    @PreAuthorize("hasPermission(null, 'things:write')")
    ResponseEntity<Void> archive(@PathVariable UUID thingId) {
        if (thingId.getLeastSignificantBits() == 0) {
            throw new BusinessRuleViolation(PlatformErrorCode.VALIDATION_FAILED, Map.of("thingId", thingId));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/things/{thingId}")
    @PreAuthorize("hasPermission(null, 'things:read')")
    ResponseEntity<Void> read(@PathVariable UUID thingId) {
        return ResponseEntity.ok().build();
    }
}
