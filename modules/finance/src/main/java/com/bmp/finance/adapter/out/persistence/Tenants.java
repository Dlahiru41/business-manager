package com.bmp.finance.adapter.out.persistence;

import com.bmp.shared.context.TenantContext;
import java.util.UUID;

/** The current tenant's id for SQL parameters. */
final class Tenants {

    private Tenants() {}

    static UUID current() {
        return TenantContext.require().value();
    }
}
