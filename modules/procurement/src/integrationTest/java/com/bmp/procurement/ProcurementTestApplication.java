package com.bmp.procurement;

import com.bmp.procurement.api.spi.ReorderSuggestion;
import com.bmp.procurement.api.spi.ReorderSuggestionProvider;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Boots procurement with the modules it depends on (tenant, catalog, party). Inventory is not here: the reorder list
 * comes from {@link StubReorderSuggestions}, the way inventory provides it through the SPI (PRO-06).
 */
@SpringBootApplication(scanBasePackages = {"com.bmp.procurement", "com.bmp.party", "com.bmp.catalog", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.procurement", "com.bmp.party", "com.bmp.catalog", "com.bmp.tenant"})
@Import(TestJwtConfiguration.class)
public class ProcurementTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-23T09:00:00Z");
    }

    @Bean
    StubReorderSuggestions reorderSuggestions() {
        return new StubReorderSuggestions();
    }

    /** Reorder suggestions a test sets per tenant. */
    public static class StubReorderSuggestions implements ReorderSuggestionProvider {

        private final Map<TenantId, List<ReorderSuggestion>> byTenant = new ConcurrentHashMap<>();

        public void set(TenantId tenant, List<ReorderSuggestion> suggestions) {
            byTenant.put(tenant, List.copyOf(suggestions));
        }

        @Override
        public List<ReorderSuggestion> suggestions(@Nullable LocationId location) {
            return byTenant.getOrDefault(TenantContext.require(), List.of()).stream()
                    .filter(s -> location == null || s.locationId().equals(location))
                    .toList();
        }
    }
}
