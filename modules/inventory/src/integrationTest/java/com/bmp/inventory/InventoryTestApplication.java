package com.bmp.inventory;

import com.bmp.inventory.api.events.StockLevelLow;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.shared.id.TenantId;
import com.bmp.test.security.TestJwtConfiguration;
import com.bmp.test.time.MutableClock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

/** Boots the inventory module with the modules it depends on (tenant, catalog) and the platform libraries. */
@SpringBootApplication(scanBasePackages = {"com.bmp.inventory", "com.bmp.catalog", "com.bmp.tenant"})
@AutoConfigurationPackage(basePackages = {"com.bmp.inventory", "com.bmp.catalog", "com.bmp.tenant"})
@Import(TestJwtConfiguration.class)
public class InventoryTestApplication {

    @Bean
    MutableClock clock() {
        return MutableClock.at("2026-11-23T09:00:00Z");
    }

    @Bean
    StockEventRecorder stockEventRecorder() {
        return new StockEventRecorder();
    }

    /**
     * Stock events published on any thread, including the asynchronous listeners' ({@code @RecordApplicationEvents}
     * sees the test thread only).
     */
    public static class StockEventRecorder {

        private final List<StockReceived> received = new CopyOnWriteArrayList<>();
        private final List<StockLevelLow> low = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(StockReceived event) {
            received.add(event);
        }

        @EventListener
        public void on(StockLevelLow event) {
            low.add(event);
        }

        public List<StockReceived> received(TenantId tenant) {
            return received.stream().filter(e -> e.tenantId().equals(tenant)).toList();
        }

        public List<StockLevelLow> low(TenantId tenant) {
            return low.stream().filter(e -> e.tenantId().equals(tenant)).toList();
        }
    }
}
