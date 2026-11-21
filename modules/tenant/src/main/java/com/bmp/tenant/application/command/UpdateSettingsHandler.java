package com.bmp.tenant.application.command;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.error.VersionConflict;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.tenant.api.events.SettingsChanged;
import com.bmp.tenant.application.BusinessActivityProbes;
import com.bmp.tenant.application.query.SettingGroupView;
import com.bmp.tenant.application.settings.SettingsCatalog;
import com.bmp.tenant.application.settings.SettingsReader;
import com.bmp.tenant.domain.error.TenantErrorCode;
import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.model.SettingGroup;
import com.bmp.tenant.domain.model.Tenant;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.SettingStore;
import com.bmp.tenant.domain.port.TenantRepository;
import com.bmp.web.security.AccessPolicy;
import io.micrometer.observation.annotation.Observed;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Changes settings of one group (TEN-03): unknown keys are rejected, values are validated against the group schema,
 * every change gets the next group version and a history row, and {@code SettingsChanged} is published. TEN-02:
 * currency and time zone are locked once business documents may exist, except for platform administrators.
 */
@Service
public class UpdateSettingsHandler {

    static final String PLATFORM_PERMISSION = "platform:tenants";
    private static final Set<String> LOCKED_GENERAL_KEYS = Set.of("currency", "timeZone");

    private final SettingStore store;
    private final SettingsCatalog catalog;
    private final SettingsReader reader;
    private final TenantRepository tenants;
    private final LocationRepository locations;
    private final BusinessActivityProbes activity;
    private final AccessPolicy access;
    private final ApplicationEventPublisher events;
    private final EventStampSource stamps;
    private final JsonMapper json;

    public UpdateSettingsHandler(
            SettingStore store,
            SettingsCatalog catalog,
            SettingsReader reader,
            TenantRepository tenants,
            LocationRepository locations,
            BusinessActivityProbes activity,
            AccessPolicy access,
            ApplicationEventPublisher events,
            EventStampSource stamps,
            JsonMapper json) {
        this.store = store;
        this.catalog = catalog;
        this.reader = reader;
        this.tenants = tenants;
        this.locations = locations;
        this.activity = activity;
        this.access = access;
        this.events = events;
        this.stamps = stamps;
        this.json = json;
    }

    /**
     * A change of some keys of one group.
     *
     * @param group group code from the URL
     * @param expectedVersion group version from {@code If-Match}
     * @param values new values by key
     */
    public record UpdateSettings(String group, long expectedVersion, Map<String, JsonNode> values) {
        public UpdateSettings {
            values = Map.copyOf(values);
        }
    }

    @Transactional
    @Observed(name = "bmp.tenant.update-settings")
    public SettingGroupView handle(UpdateSettings command) {
        SettingGroup group = SettingsReader.group(command.group());
        store.lock();
        SettingGroupView current = reader.read(group);
        if (current.version() != command.expectedVersion()) {
            throw new VersionConflict(current.version());
        }
        rejectUnknownKeys(group, command.values().keySet());
        ObjectNode updated = current.values().deepCopy();
        command.values().forEach(updated::set);
        validate(group, updated);
        List<String> changed = command.values().keySet().stream()
                .filter(key -> !updated.get(key).equals(current.values().get(key)))
                .sorted()
                .toList();
        if (changed.isEmpty()) {
            return current;
        }
        TenantId tenant = TenantContext.require();
        enforceLocks(group, changed, tenant);
        long version = current.version() + 1;
        Map<String, SettingStore.StoredValue> stored = store.load(group);
        List<SettingsChanged.Change> changes = new ArrayList<>();
        for (String key : changed) {
            String value = json.writeValueAsString(updated.get(key));
            SettingStore.StoredValue previous = stored.get(key);
            store.write(group, key, value, previous != null ? previous.json() : null, version);
            changes.add(new SettingsChanged.Change(key, value));
        }
        if (group == SettingGroup.GENERAL) {
            mirrorRegional(tenant, updated);
        }
        var stamp = stamps.next();
        events.publishEvent(new SettingsChanged(
                stamp.eventId(),
                tenant,
                stamp.occurredAt(),
                tenant.value(),
                version,
                stamp.metadata(),
                group.code(),
                changes));
        return new SettingGroupView(group.code(), version, updated);
    }

    private void rejectUnknownKeys(SettingGroup group, Set<String> keys) {
        List<FieldError> unknown = keys.stream()
                .filter(key -> !catalog.keys(group).contains(key))
                .sorted()
                .map(key -> new FieldError(key, "UNKNOWN_SETTING", "is not a setting of group " + group.code()))
                .toList();
        if (!unknown.isEmpty()) {
            throw new ValidationFailure(TenantErrorCode.UNKNOWN_SETTING, unknown);
        }
    }

    private void validate(SettingGroup group, ObjectNode document) {
        List<FieldError> errors = new ArrayList<>(catalog.validate(group, document));
        if (errors.isEmpty()) {
            errors.addAll(semanticErrors(group, document));
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailure(TenantErrorCode.INVALID_SETTING_VALUE, errors);
        }
    }

    private List<FieldError> semanticErrors(SettingGroup group, ObjectNode document) {
        List<FieldError> errors = new ArrayList<>();
        if (group == SettingGroup.GENERAL) {
            try {
                CurrencyCode.of(document.get("currency").asString());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("currency", "UNKNOWN_CURRENCY", "is not an ISO 4217 currency"));
            }
            try {
                ZoneId.of(document.get("timeZone").asString());
            } catch (DateTimeException e) {
                errors.add(new FieldError("timeZone", "UNKNOWN_TIME_ZONE", "is not an IANA time zone"));
            }
        }
        if (group == SettingGroup.INVENTORY && document.get("defaultLocationId").isString()) {
            UUID location = UUID.fromString(document.get("defaultLocationId").asString());
            if (!locations.findById(location).map(Location::isActive).orElse(false)) {
                errors.add(new FieldError("defaultLocationId", "UNKNOWN_LOCATION", "is not an active location"));
            }
        }
        return errors;
    }

    private void enforceLocks(SettingGroup group, List<String> changed, TenantId tenant) {
        if (group != SettingGroup.GENERAL) {
            return;
        }
        for (String key : changed) {
            // Rule TEN-02: currency and time zone cannot change once any business document exists.
            if (LOCKED_GENERAL_KEYS.contains(key)
                    && activity.hasBusinessDocuments(tenant)
                    && !access.isGranted(PLATFORM_PERMISSION)) {
                throw new BusinessRuleViolation(TenantErrorCode.SETTING_LOCKED, Map.of("key", key));
            }
        }
    }

    private void mirrorRegional(TenantId tenantId, ObjectNode general) {
        Tenant tenant = tenants.findById(tenantId.value())
                .orElseThrow(
                        () -> new NotFoundException(TenantErrorCode.TENANT_NOT_FOUND, Map.of("tenantId", tenantId)));
        tenant.changeRegional(new Tenant.Regional(
                CurrencyCode.of(general.get("currency").asString()),
                ZoneId.of(general.get("timeZone").asString()),
                general.get("businessDayCutoffHour").asInt()));
        tenants.save(tenant);
    }
}
