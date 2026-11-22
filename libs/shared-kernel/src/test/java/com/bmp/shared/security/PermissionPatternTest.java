package com.bmp.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PermissionPatternTest {

    @ParameterizedTest
    @CsvSource({
        "sales:void, sales:void, true",
        "sales:void, sales:create, false",
        "inventory:*, inventory:adjust, true",
        "inventory:*, sales:create, false",
        "*, tenant:settings, true",
        "*, platform:tenants, false",
        "platform:*, platform:tenants, true",
        "*, NOT-A-PERMISSION, false",
        "sales:*, sales, false"
    })
    void shouldMatchGrantsAgainstPermissions(String grant, String permission, boolean expected) {
        assertThat(PermissionPattern.matches(grant, permission)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"sales:void, true", "*, true", "inventory:*, true", "Sales:void, false", "sales, false", "*:*, false"})
    void shouldValidateGrants(String grant, boolean expected) {
        assertThat(PermissionPattern.isValidGrant(grant)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(SystemRole.class)
    void systemRoleDefaultsShouldBeValidGrants(SystemRole role) {
        assertThat(role.defaultPermissions()).allMatch(PermissionPattern::isValidGrant);
        assertThat(SystemRole.fromCode(role.code())).contains(role);
    }

    @ParameterizedTest
    @CsvSource({
        "OWNER, tenant:settings, true",
        "OWNER, platform:tenants, false",
        "CASHIER, sales:create, true",
        "CASHIER, sales:void, false",
        "MANAGER, inventory:adjust, true",
        "DEVICE, sync:upload, true",
        "STOREKEEPER, inventory:receive, true",
        "ACCOUNTANT, finance:close-period, true",
        "platform_admin, platform:tenants, true",
        "platform_admin, iam:users, true"
    })
    void shouldGrantDocumentedDefaults(String roleCode, String permission, boolean expected) {
        SystemRole role = SystemRole.fromCode(roleCode).orElseThrow();

        assertThat(PermissionPattern.anyMatches(List.copyOf(role.defaultPermissions()), permission))
                .isEqualTo(expected);
        assertThat(role.isTenantRole()).isEqualTo(role != SystemRole.PLATFORM_ADMIN);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "*                        | *             | true",
                "sales:*                  | *             | false",
                "*                        | sales:*       | true",
                "sales:*                  | sales:*       | true",
                "sales:create,sales:void  | sales:*       | false",
                "*                        | platform:*    | false",
                "sales:*                  | sales:void    | true",
                "catalog:read             | sales:void    | false",
                "*                        | platform:tenants | false"
            })
    void shouldTellWhetherHeldGrantsCoverAGrant(String held, String grant, boolean expected) {
        assertThat(PermissionPattern.coveredBy(List.of(held.split(",", -1)), grant))
                .isEqualTo(expected);
    }
}
