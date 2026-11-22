package com.bmp.tenant.application.command;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.VersionConflict;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.application.TenantStatusLookup;
import com.bmp.tenant.application.query.TenantView;
import com.bmp.tenant.domain.error.TenantErrorCode;
import com.bmp.tenant.domain.model.Tenant;
import com.bmp.tenant.domain.port.TenantRepository;
import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant profile updates (current tenant) and status changes (platform administration, TEN-04). */
@Service
public class TenantProfileHandlers {

    private static final Logger log = LoggerFactory.getLogger(TenantProfileHandlers.class);

    private final TenantRepository tenants;
    private final TenantStatusLookup statusLookup;
    private final TransactionTemplate transaction;

    public TenantProfileHandlers(
            TenantRepository tenants, TenantStatusLookup statusLookup, PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.statusLookup = statusLookup;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * PATCH semantics: {@code null} fields stay unchanged.
     *
     * @param expectedVersion from {@code If-Match}
     */
    public record UpdateProfile(
            long expectedVersion,
            @Nullable String name,
            @Nullable String legalName,
            @Nullable String businessType) {}

    /** Action on a tenant's lifecycle. */
    public enum StatusAction {
        SUSPEND,
        REACTIVATE
    }

    @Transactional
    @Observed(name = "bmp.tenant.update-profile")
    public TenantView updateProfile(UpdateProfile command) {
        Tenant tenant = load(TenantContext.require());
        if (tenant.version() != command.expectedVersion()) {
            throw new VersionConflict(tenant.version());
        }
        Tenant.TenantProfile current = tenant.profile();
        tenant.updateProfile(new Tenant.TenantProfile(
                Objects.requireNonNullElse(command.name(), current.name()),
                command.legalName() != null ? command.legalName() : current.legalName(),
                Objects.requireNonNullElse(command.businessType(), current.businessType())));
        return TenantView.of(tenants.saveAndFlush(tenant));
    }

    /** Suspends or reactivates {@code tenantId}; runs in that tenant's context, not the caller's. */
    @Observed(name = "bmp.tenant.change-status")
    public TenantView changeStatus(TenantId tenantId, StatusAction action, String reason) {
        TenantView changed = TenantContext.callWith(
                tenantId,
                () -> transaction.execute(status -> {
                    Tenant tenant = load(tenantId);
                    switch (action) {
                        case SUSPEND -> tenant.suspend();
                        case REACTIVATE -> tenant.reactivate();
                    }
                    return TenantView.of(tenants.saveAndFlush(tenant));
                }));
        statusLookup.evict(tenantId);
        log.atInfo()
                .addKeyValue("tenantId", tenantId.toString())
                .addKeyValue("action", action.name())
                .log("Tenant status changed tenantId={} action={} reasonLength={}", tenantId, action, reason.length());
        return Objects.requireNonNull(changed);
    }

    private Tenant load(TenantId id) {
        return tenants.findById(id.value())
                .orElseThrow(() -> new NotFoundException(TenantErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", id)));
    }
}
