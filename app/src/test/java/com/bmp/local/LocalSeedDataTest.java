package com.bmp.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bmp.iam.api.IamProvisioning;
import com.bmp.iam.api.dto.ExistingAccount;
import com.bmp.shared.id.UserId;
import com.bmp.tenant.api.TenantProvisioning;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

class LocalSeedDataTest {

    @Test
    void seedsBothTenantsWithTheRealmUsers() {
        TenantProvisioning tenants = mock(TenantProvisioning.class);
        IamProvisioning iam = mock(IamProvisioning.class);

        new LocalSeedData(tenants, iam).run(new DefaultApplicationArguments());

        verify(tenants).provision(eq(LocalSeedData.PILOT_SHOP), any());
        verify(tenants).provision(eq(LocalSeedData.SECOND_SHOP), any());
        ArgumentCaptor<ExistingAccount> accounts = ArgumentCaptor.forClass(ExistingAccount.class);
        verify(iam, times(10)).provision(any(), accounts.capture());
        assertThat(accounts.getAllValues().getFirst().subject())
                .isEqualTo(UserId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000b01")));
        assertThat(accounts.getAllValues().getFirst().roleCodes()).containsExactly("OWNER");
        assertThat(accounts.getAllValues().getLast().subject())
                .isEqualTo(UserId.of(UUID.fromString("01934f5a-6d1c-7000-8000-000000000b15")));
        assertThat(accounts.getAllValues().getLast().displayName()).isEqualTo("second-accountant");
    }
}
