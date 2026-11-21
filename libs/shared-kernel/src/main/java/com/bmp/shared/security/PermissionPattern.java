package com.bmp.shared.security;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Matches permissions ({@code <area>:<action>}, e.g. {@code sales:void}) against granted patterns:
 *
 * <ul>
 *   <li>an exact permission, e.g. {@code sales:void};
 *   <li>{@code <area>:*}, every action of one area, e.g. {@code inventory:*};
 *   <li>{@code *}, every tenant permission (never {@code platform:*} ones).
 * </ul>
 */
public final class PermissionPattern {

    /** Grants every tenant permission. */
    public static final String ALL_TENANT_PERMISSIONS = "*";

    private static final String PLATFORM_AREA = "platform";
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z-]*:[a-z][a-z-]*");
    private static final Pattern GRANT = Pattern.compile("\\*|[a-z][a-z-]*:(\\*|[a-z][a-z-]*)");

    private PermissionPattern() {}

    public static boolean isValidPermission(String permission) {
        return PERMISSION.matcher(permission).matches();
    }

    public static boolean isValidGrant(String grant) {
        return GRANT.matcher(grant).matches();
    }

    /** Whether {@code grant} covers {@code permission}. Malformed permissions are never granted. */
    public static boolean matches(String grant, String permission) {
        if (!isValidPermission(permission)) {
            return false;
        }
        String area = permission.substring(0, permission.indexOf(':'));
        if (ALL_TENANT_PERMISSIONS.equals(grant)) {
            return !PLATFORM_AREA.equals(area);
        }
        if (grant.endsWith(":*")) {
            return grant.substring(0, grant.length() - 2).equals(area);
        }
        return grant.equals(permission);
    }

    /** Whether any of {@code grants} covers {@code permission}. */
    public static boolean anyMatches(Collection<String> grants, String permission) {
        return grants.stream().anyMatch(grant -> matches(grant, permission));
    }
}
