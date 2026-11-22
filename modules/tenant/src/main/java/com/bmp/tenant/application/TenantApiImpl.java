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
import com.bmp.tenant.application.settings.SettingsReader;
import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.TenantRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Implements the public API of the tenant module for other modules. */
@Component
class TenantApiImpl implements TenantQueries, TenantProvisioning {

    private final TenantRepository tenants;
    private final LocationRepository locations;
    private final CreateTenantHandler createTenant;
    private final SettingsReader settings;
    private final TransactionTemplate readOnly;

    TenantApiImpl(
            TenantRepository tenants,
            LocationRepository locations,
            CreateTenantHandler createTenant,
            SettingsReader settings,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.locations = locations;
        this.createTenant = createTenant;
        this.settings = settings;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        // Its own transaction: the tenant is fixed per transaction, so a lookup for another tenant must not join one.
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    public Optional<TenantSummary> currentTenant() {
        return tenants.findById(TenantContext.require().value())
                .map(TenantView::of)
                .map(TenantApiImpl::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveLocation(LocationId location) {
        return locations.findById(location.value()).map(Location::isActive).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> setting(String group, String key) {
        return SettingGroup.fromCode(group)
                .map(settings::read)
                .flatMap(view -> Optional.ofNullable(view.values().get(key)))
                .map(Object::toString);
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
