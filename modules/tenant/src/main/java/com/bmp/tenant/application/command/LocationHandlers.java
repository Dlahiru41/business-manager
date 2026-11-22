package com.bmp.tenant.application.command;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.VersionConflict;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.LocationId;
import com.bmp.tenant.application.query.LocationView;
import com.bmp.tenant.domain.error.TenantErrorCode;
import com.bmp.tenant.domain.model.Location;
import com.bmp.tenant.domain.model.LocationStatus;
import com.bmp.tenant.domain.model.LocationType;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.TenantRepository;
import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating and changing locations; TEN-01 keeps at least one active location per tenant. */
@Service
public class LocationHandlers {

    private final LocationRepository locations;
    private final TenantRepository tenants;
    private final ApplicationEventPublisher events;
    private final EventStampSource stamps;
    private final IdGenerator ids;

    public LocationHandlers(
            LocationRepository locations,
            TenantRepository tenants,
            ApplicationEventPublisher events,
            EventStampSource stamps,
            IdGenerator ids) {
        this.locations = locations;
        this.tenants = tenants;
        this.events = events;
        this.stamps = stamps;
        this.ids = ids;
    }

    /**
     * New location.
     *
     * @param address JSON document, or {@code null}
     */
    public record CreateLocation(
            String name, LocationType type, @Nullable String address) {}

    /**
     * PATCH semantics: {@code null} fields stay unchanged; {@code addressSet} distinguishes "clear" from "keep".
     *
     * @param expectedVersion from {@code If-Match}
     */
    public record UpdateLocation(
            UUID id,
            long expectedVersion,
            @Nullable String name,
            @Nullable LocationType type,
            boolean addressSet,
            @Nullable String address,
            @Nullable LocationStatus status) {}

    @Transactional
    @Observed(name = "bmp.tenant.create-location")
    public LocationView create(CreateLocation command) {
        Location location = Location.create(
                ids.next(LocationId::new),
                TenantContext.require(),
                command.name(),
                command.type(),
                command.address(),
                stamps);
        Location saved = locations.save(location);
        location.pullEvents().forEach(events::publishEvent);
        return LocationView.of(saved);
    }

    @Transactional
    @Observed(name = "bmp.tenant.update-location")
    public LocationView update(UpdateLocation command) {
        Location location = locations
                .findById(command.id())
                .orElseThrow(() ->
                        new NotFoundException(TenantErrorCode.LOCATION_NOT_FOUND, Map.of("locationId", command.id())));
        if (location.version() != command.expectedVersion()) {
            throw new VersionConflict(location.version());
        }
        if (command.name() != null) {
            location.rename(command.name());
        }
        if (command.type() != null) {
            location.changeType(command.type());
        }
        if (command.addressSet()) {
            location.changeAddress(command.address());
        }
        applyStatus(location, command.status());
        return LocationView.of(locations.saveAndFlush(location));
    }

    private void applyStatus(Location location, @Nullable LocationStatus status) {
        if (status == LocationStatus.INACTIVE && location.isActive()) {
            // Rule TEN-01: a tenant has at least one (active) location. The tenant row lock serializes concurrent
            // deactivations, which could otherwise each see the other location still active.
            tenants.lockById(TenantContext.require().value());
            if (locations.countByStatus(LocationStatus.ACTIVE) <= 1) {
                throw new BusinessRuleViolation(
                        TenantErrorCode.LAST_ACTIVE_LOCATION, Map.of("locationId", location.id()));
            }
            location.deactivate();
        } else if (status == LocationStatus.ACTIVE) {
            location.activate();
        }
    }
}
