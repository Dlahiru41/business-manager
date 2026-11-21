package com.bmp.web.security;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a validated JWT into a {@link BmpAuthentication}: {@code sub} (UUID) becomes the user id, {@code tenant_id}
 * and {@code device_id} the tenant and device, {@code roles} the tenant roles, and {@code platform_admin} in Keycloak's
 * {@code realm_access.roles} the platform role.
 * Malformed identity claims make the token invalid (401), they are never guessed.
 */
public class BmpJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String TENANT_CLAIM = "tenant_id";
    public static final String DEVICE_CLAIM = "device_id";
    public static final String ROLES_CLAIM = "roles";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UserId user = UserId.of(uuid(jwt.getSubject(), "sub"));
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);
        String device = jwt.getClaimAsString(DEVICE_CLAIM);
        Set<String> roles = roles(jwt);
        BmpPrincipal principal = new BmpPrincipal(
                user,
                tenant != null ? TenantId.of(uuid(tenant, TENANT_CLAIM)) : null,
                roles,
                device != null ? DeviceId.of(uuid(device, DEVICE_CLAIM)) : null);
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new BmpAuthentication(jwt, principal, authorities);
    }

    private static Set<String> roles(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        Collection<String> flat = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (flat != null) {
            roles.addAll(flat);
        }
        // Realm roles only ever grant the platform role (ADR-016); Keycloak's built-in realm roles are ignored.
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null
                && realmAccess.get("roles") instanceof Collection<?> realmRoles
                && realmRoles.contains(SystemRole.PLATFORM_ADMIN.code())) {
            roles.add(SystemRole.PLATFORM_ADMIN.code());
        }
        return roles;
    }

    private static UUID uuid(@Nullable String value, String claim) {
        if (value == null) {
            throw new InvalidBearerTokenException("Missing claim " + claim);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Claim " + claim + " is not a UUID", e);
        }
    }
}
