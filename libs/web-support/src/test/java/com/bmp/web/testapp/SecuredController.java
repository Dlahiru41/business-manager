package com.bmp.web.testapp;

import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/test-secured")
public class SecuredController {

    @GetMapping
    @PreAuthorize("hasPermission(null, 'tenant:read')")
    public Map<String, String> read() {
        return Map.of(
                "tenant", TenantContext.require().toString(),
                "user", String.valueOf(ActorContext.currentOrSystem().userId()));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'tenant:write')")
    public Map<String, String> write() {
        return Map.of("tenant", TenantContext.require().toString());
    }

    @GetMapping("/platform")
    @PreAuthorize("hasPermission(null, 'platform:tenants')")
    public Map<String, Boolean> platform() {
        return Map.of("tenantBound", TenantContext.current().isPresent());
    }

    @GetMapping("/sales")
    @PreAuthorize("hasPermission(null, 'sales:create')")
    public Map<String, String> sales() {
        return Map.of("tenant", TenantContext.require().toString());
    }
}
