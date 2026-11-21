package com.bmp.local;

import com.bmp.iam.api.IamProvisioning;
import com.bmp.iam.api.dto.ExistingAccount;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.tenant.api.TenantProvisioning;
import com.bmp.tenant.api.dto.NewTenant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local profile only: creates the two test tenants and the profiles of the users of
 * {@code deploy/local/keycloak/realm-bmp.json}, with the same ids, so a token from local Keycloak works at once.
 * Idempotent, so it runs on every start.
 */
@Component
@Profile("local")
class LocalSeedData implements ApplicationRunner {

    static final TenantId PILOT_SHOP = TenantId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000a01"));
    static final TenantId SECOND_SHOP = TenantId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000a02"));

    private static final Logger log = LoggerFactory.getLogger(LocalSeedData.class);
    private static final List<String> ROLES = List.of("OWNER", "MANAGER", "CASHIER", "STOREKEEPER", "ACCOUNTANT");

    private final TenantProvisioning tenants;
    private final IamProvisioning iam;

    LocalSeedData(TenantProvisioning tenants, IamProvisioning iam) {
        this.tenants = tenants;
        this.iam = iam;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(PILOT_SHOP, "Pilot shop", "pilot", 0x01);
        seed(SECOND_SHOP, "Second shop", "second", 0x11);
        log.info("Local seed data ready tenants={}", List.of(PILOT_SHOP, SECOND_SHOP));
    }

    /** Users of a tenant have ids ...0b01 to ...0b05 (pilot) and ...0b11 to ...0b15 (second), one per role. */
    private void seed(TenantId tenant, String name, String prefix, int firstUser) {
        tenants.provision(tenant, new NewTenant(name, null, "RETAIL_GROCERY", null, null, null, null, null));
        for (int i = 0; i < ROLES.size(); i++) {
            String role = ROLES.get(i);
            UserId user = UserId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000b%02x".formatted(firstUser + i)));
            iam.provision(
                    tenant, new ExistingAccount(user, prefix + "-" + role.toLowerCase(Locale.ROOT), List.of(role)));
        }
    }
}
