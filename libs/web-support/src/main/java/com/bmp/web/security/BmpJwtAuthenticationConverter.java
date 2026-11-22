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
    public static final String CLIENT_CLAIM = "azp";

    /** Client id prefix of device clients, {@code device-<deviceId>} (ADR-016). */
    public static final String DEVICE_CLIENT_PREFIX = "device-";

    private final Set<String> platformAdminClients;

    /**
     * @param platformAdminClients clients ({@code azp}) through which a platform administrator may sign in; empty
     *     means no token is ever a platform administrator token
     */
    public BmpJwtAuthenticationConverter(Collection<String> platformAdminClients) {
        this.platformAdminClients = Set.copyOf(platformAdminClients);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UserId user = UserId.of(uuid(jwt.getSubject(), "sub"));
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);
        String device = jwt.getClaimAsString(DEVICE_CLAIM);
        String client = jwt.getClaimAsString(CLIENT_CLAIM);
        Set<String> roles = isDeviceToken(device, client) ? deviceRoles(device, client) : userRoles(jwt, client);
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

    private static boolean isDeviceToken(@Nullable String device, @Nullable String client) {
        return device != null || (client != null && client.startsWith(DEVICE_CLIENT_PREFIX));
    }

    /** A device token comes from the device's own client and holds the device role, nothing else. */
    private static Set<String> deviceRoles(@Nullable String device, @Nullable String client) {
        if (device == null || !(DEVICE_CLIENT_PREFIX + device).equals(client)) {
            throw new InvalidBearerTokenException("Device token does not match its client");
        }
        return Set.of(SystemRole.DEVICE.code());
    }

    /**
     * Tenant roles come from the flat {@code roles} claim, never the device or platform role; the platform role comes
     * only from Keycloak's {@code realm_access.roles} and only through an allowed client (ADR-016).
     */
    private Set<String> userRoles(Jwt jwt, @Nullable String client) {
        Set<String> roles = new LinkedHashSet<>();
        Collection<String> flat = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (flat != null) {
            flat.stream()
                    .filter(role ->
                            !role.equals(SystemRole.PLATFORM_ADMIN.code()) && !role.equals(SystemRole.DEVICE.code()))
                    .forEach(roles::add);
        }
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (client != null
                && platformAdminClients.contains(client)
                && realmAccess != null
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
