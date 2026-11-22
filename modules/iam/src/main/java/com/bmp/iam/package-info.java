/**
 * IAM module: users, roles and permissions, the caller's identity and registered devices. Credentials live in the
 * identity provider (Keycloak) and are reached only through {@code IdentityProviderGateway}; this module never stores
 * a password or a device secret.
 */
@ApplicationModule(
        displayName = "Identity and access",
        allowedDependencies = {"tenant :: api"})
@NullMarked
package com.bmp.iam;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
