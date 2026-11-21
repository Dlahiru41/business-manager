package com.bmp.iam.application;

import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.port.DeviceRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.web.security.DeviceStatusProvider;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Device status for request authorization (IAM-03): a revoked device is rejected at once on the instance that revoked
 * it (the entry is evicted) and within the 60 second cache lifetime on every other instance. Unknown devices are
 * rejected.
 */
@Component
public class DeviceStatusLookup implements DeviceStatusProvider {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long MAX_DEVICES = 50_000;

    private final DeviceRepository devices;
    private final TransactionTemplate readOnly;
    private final Cache<Key, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(MAX_DEVICES)
            .build();

    public DeviceStatusLookup(DeviceRepository devices, PlatformTransactionManager transactionManager) {
        this.devices = devices;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    private record Key(TenantId tenant, DeviceId device) {}

    @Override
    public boolean isActive(TenantId tenant, DeviceId device) {
        return cache.get(new Key(tenant, device), this::load);
    }

    public void evict(TenantId tenant, DeviceId device) {
        cache.invalidate(new Key(tenant, device));
    }

    private boolean load(Key key) {
        return Objects.requireNonNull(TenantContext.callWith(
                key.tenant(),
                () -> readOnly.execute(tx -> devices.findById(key.device().value())
                        .map(Device::isActive)
                        .orElse(false))));
    }
}
