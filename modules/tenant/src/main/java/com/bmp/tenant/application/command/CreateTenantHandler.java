package com.bmp.tenant.application.command;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.tenant.api.dto.NewTenant;
import com.bmp.tenant.application.query.TenantView;
import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.model.LocationType;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.model.Tenant;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.SettingStore;
import com.bmp.tenant.domain.port.TenantRepository;
import io.micrometer.observation.annotation.Observed;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates a tenant together with its default location "Main shop" (TEN-01) and initial settings, publishing
 * {@code TenantCreated} and {@code LocationCreated}. The transaction is started programmatically, <em>after</em> the
 * new tenant is bound, because row-level security needs the tenant from the first statement.
 */
@Service
public class CreateTenantHandler {

    static final String DEFAULT_CURRENCY = "LKR";
    static final String DEFAULT_TIME_ZONE = "Asia/Colombo";
    static final long INITIAL_SETTINGS_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(CreateTenantHandler.class);

    private final TenantRepository tenants;
    private final LocationRepository locations;
    private final SettingStore settings;
    private final ApplicationEventPublisher events;
    private final EventStampSource stamps;
    private final IdGenerator ids;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public CreateTenantHandler(
            TenantRepository tenants,
            LocationRepository locations,
            SettingStore settings,
            ApplicationEventPublisher events,
            EventStampSource stamps,
            IdGenerator ids,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.locations = locations;
        this.settings = settings;
        this.events = events;
        this.stamps = stamps;
        this.ids = ids;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Creates a tenant with a generated id. */
    @Observed(name = "bmp.tenant.create-tenant")
    public TenantView handle(NewTenant spec) {
        return create(ids.next(TenantId::new), spec);
    }

    /** Creates a tenant with a known id, or returns the existing one (idempotent provisioning). */
    public TenantView provision(TenantId id, NewTenant spec) {
        Optional<TenantView> existing = TenantContext.callWith(
                id,
                () -> transaction.execute(status -> tenants.findById(id.value()).map(TenantView::of)));
        return Objects.requireNonNull(existing).orElseGet(() -> create(id, spec));
    }

    private TenantView create(TenantId id, NewTenant spec) {
        Tenant.Regional regional = regional(spec);
        TenantView created = Objects.requireNonNull(TenantContext.callWith(
                id,
                () -> transaction.execute(status -> {
                    Tenant tenant = Tenant.create(
                            id,
                            new Tenant.TenantProfile(spec.name(), spec.legalName(), spec.businessType()),
                            regional,
                            spec.plan(),
                            spec.templatePackId(),
                            stamps);
                    tenants.save(tenant);
                    Location main = Location.create(
                            ids.next(LocationId::new),
                            id,
                            Location.MAIN_LOCATION_NAME,
                            LocationType.SHOP,
                            null,
                            stamps);
                    locations.save(main);
                    writeInitialSettings(regional, main.id());
                    List<DomainEvent> recorded = new ArrayList<>(tenant.pullEvents());
                    recorded.addAll(main.pullEvents());
                    recorded.forEach(events::publishEvent);
                    return TenantView.of(tenant);
                })));
        log.atInfo()
                .addKeyValue("tenantId", id.toString())
                .log("Tenant created tenantId={} currency={}", id, regional.currency());
        return created;
    }

    private void writeInitialSettings(Tenant.Regional regional, LocationId mainLocation) {
        settings.write(
                SettingGroup.GENERAL, "currency", quoted(regional.currency().code()), null, INITIAL_SETTINGS_VERSION);
        settings.write(
                SettingGroup.GENERAL, "timeZone", quoted(regional.timeZone().getId()), null, INITIAL_SETTINGS_VERSION);
        settings.write(
                SettingGroup.GENERAL,
                "businessDayCutoffHour",
                Integer.toString(regional.businessDayCutoffHour()),
                null,
                INITIAL_SETTINGS_VERSION);
        settings.write(
                SettingGroup.INVENTORY,
                "defaultLocationId",
                quoted(mainLocation.toString()),
                null,
                INITIAL_SETTINGS_VERSION);
    }

    private String quoted(String value) {
        return json.writeValueAsString(value);
    }

    private static Tenant.Regional regional(NewTenant spec) {
        List<FieldError> errors = new ArrayList<>();
        CurrencyCode currency = currency(spec.currency(), errors);
        ZoneId zone = zone(spec.timeZone(), errors);
        Integer cutoff = spec.businessDayCutoffHour();
        int hour = cutoff != null ? cutoff : 0;
        if (hour < 0 || hour > 23) {
            errors.add(new FieldError("businessDayCutoffHour", "RANGE", "must be between 0 and 23"));
        }
        if (!errors.isEmpty() || currency == null || zone == null) {
            throw new ValidationFailure(errors);
        }
        return new Tenant.Regional(currency, zone, hour);
    }

    /**
     * Money rounds to two decimals (RoundingPolicy) until rounding follows each currency's minor units; until then a
     * currency with another number of decimals (JPY, KWD, ...) is refused rather than rounded wrongly.
     */
    static final FieldError TWO_DECIMALS_ONLY =
            new FieldError("currency", "UNSUPPORTED_CURRENCY", "only currencies with two decimals are supported");

    static boolean hasTwoDecimals(CurrencyCode currency) {
        return Currency.getInstance(currency.code()).getDefaultFractionDigits() == 2;
    }

    private static @Nullable CurrencyCode currency(@Nullable String code, List<FieldError> errors) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(code != null ? code : DEFAULT_CURRENCY);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError("currency", "UNKNOWN_CURRENCY", "is not an ISO 4217 currency"));
            return null;
        }
        if (!hasTwoDecimals(currency)) {
            errors.add(TWO_DECIMALS_ONLY);
            return null;
        }
        return currency;
    }

    private static @Nullable ZoneId zone(@Nullable String id, List<FieldError> errors) {
        try {
            return ZoneId.of(id != null ? id : DEFAULT_TIME_ZONE);
        } catch (DateTimeException e) {
            errors.add(new FieldError("timeZone", "UNKNOWN_TIME_ZONE", "is not an IANA time zone"));
            return null;
        }
    }
}
