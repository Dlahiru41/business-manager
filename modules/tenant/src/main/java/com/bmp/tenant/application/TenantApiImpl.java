package com.bmp.tenant.application;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.TenantProvisioning;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.NewTenant;
import com.bmp.tenant.api.dto.TenantSummary;
import com.bmp.tenant.application.command.CreateTenantHandler;
import com.bmp.tenant.application.query.TenantView;
import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.TenantRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Implements the public API of the tenant module for other modules. */
@Component
class TenantApiImpl implements TenantQueries, TenantProvisioning {

    private final TenantRepository tenants;
    private final LocationRepository locations;
    private final CreateTenantHandler createTenant;
    private final TransactionTemplate readOnly;

    TenantApiImpl(
            TenantRepository tenants,
            LocationRepository locations,
            CreateTenantHandler createTenant,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.locations = locations;
        this.createTenant = createTenant;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<TenantSummary> findTenant(TenantId tenant) {
        Optional<TenantView> view = Objects.requireNonNull(TenantContext.callWith(
                tenant,
                () -> readOnly.execute(tx -> tenants.findById(tenant.value()).map(TenantView::of))));
        return view.map(TenantApiImpl::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveLocation(LocationId location) {
        return locations.findById(location.value()).map(Location::isActive).orElse(false);
    }

    @Override
    public TenantSummary provision(TenantId id, NewTenant spec) {
        return summary(createTenant.provision(id, spec));
    }

    private static TenantSummary summary(TenantView view) {
        return new TenantSummary(
                TenantId.of(view.id()),
                view.name(),
                view.currency(),
                view.timeZone(),
                view.businessDayCutoffHour(),
                view.status());
    }
}
