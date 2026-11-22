/**
 * Tenant module: tenants, locations, business settings and receipt templates. The most upstream module: it depends on
 * no other module, and behaviour it needs from downstream modules is reached through SPIs in {@code api.spi}.
 */
@ApplicationModule(
        displayName = "Tenant",
        allowedDependencies = {})
@NullMarked
package com.bmp.tenant;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
