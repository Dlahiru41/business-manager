package com.bmp.tenant.application;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.TenantProvisioning;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.NewTenant;
import com.bmp.tenant.api.dto.ReceiptTemplateInfo;
import com.bmp.tenant.api.dto.TenantSummary;
import com.bmp.tenant.application.command.CreateTenantHandler;
import com.bmp.tenant.application.query.ReceiptTemplateView;
import com.bmp.tenant.application.query.SettingGroupView;
import com.bmp.tenant.application.query.TenantView;
import com.bmp.tenant.application.settings.SettingsReader;
import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.ReceiptTemplateRepository;
import com.bmp.tenant.domain.port.TenantRepository;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Implements the public API of the tenant module for other modules. */
@Component
class TenantApiImpl implements TenantQueries, TenantProvisioning {

    /** Key of the per-transaction settings memo. */
    private static final Object SETTINGS_MEMO = new Object();

    private final TenantRepository tenants;
    private final LocationRepository locations;
    private final CreateTenantHandler createTenant;
    private final SettingsReader settings;
    private final ReceiptTemplateRepository templates;
    private final TransactionTemplate readOnly;

    TenantApiImpl(
            TenantRepository tenants,
            LocationRepository locations,
            CreateTenantHandler createTenant,
            SettingsReader settings,
            ReceiptTemplateRepository templates,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.locations = locations;
        this.createTenant = createTenant;
        this.settings = settings;
        this.templates = templates;
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
                .map(this::read)
                .flatMap(view -> Optional.ofNullable(view.values().get(key)))
                .map(Object::toString);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceiptTemplateInfo> defaultReceiptTemplate() {
        return templates
                .findDefault()
                .map(ReceiptTemplateView::of)
                .map(t -> new ReceiptTemplateInfo(
                        t.id(), t.version(), t.paperWidthMm(), t.header(), t.footer(), t.fields(), t.language()));
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

    /**
     * A group's settings, read once per transaction: a sale asks for several keys of the same groups, and the tenant
     * is fixed for the transaction. Outside a transaction every call reads.
     */
    @SuppressWarnings("unchecked") // the resource is only ever bound to this map type
    private SettingGroupView read(SettingGroup group) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return settings.read(group);
        }
        Map<SettingGroup, SettingGroupView> memo =
                (Map<SettingGroup, SettingGroupView>) TransactionSynchronizationManager.getResource(SETTINGS_MEMO);
        if (memo == null) {
            Map<SettingGroup, SettingGroupView> fresh = new EnumMap<>(SettingGroup.class);
            TransactionSynchronizationManager.bindResource(SETTINGS_MEMO, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(SETTINGS_MEMO);
                }
            });
            memo = fresh;
        }
        return memo.computeIfAbsent(group, settings::read);
    }
}
