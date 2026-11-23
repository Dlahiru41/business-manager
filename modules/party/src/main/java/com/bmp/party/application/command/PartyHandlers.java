package com.bmp.party.application.command;

import com.bmp.party.application.PartyAccess;
import com.bmp.party.application.PartyEvents;
import com.bmp.party.application.PartyLoader;
import com.bmp.party.application.query.PartyView;
import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.party.domain.model.Address;
import com.bmp.party.domain.model.CreditPolicy;
import com.bmp.party.domain.model.CustomerGroup;
import com.bmp.party.domain.model.Party;
import com.bmp.party.domain.model.PartyStatus;
import com.bmp.party.domain.model.PartyType;
import com.bmp.party.domain.model.PhoneNumbers;
import com.bmp.party.domain.port.CustomerGroupRepository;
import com.bmp.party.domain.port.PartyRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds and changes parties (PTY-01..PTY-04); every change publishes PartyCreated or PartyUpdated. */
@Service
public class PartyHandlers {

    private static final Logger log = LoggerFactory.getLogger(PartyHandlers.class);

    private final PartyRepository parties;
    private final CustomerGroupRepository groups;
    private final PartyLoader loader;
    private final PartyAccess access;
    private final PartyEvents events;
    private final IdGenerator ids;
    private final Clock clock;

    public PartyHandlers(
            PartyRepository parties,
            CustomerGroupRepository groups,
            PartyLoader loader,
            PartyAccess access,
            PartyEvents events,
            IdGenerator ids,
            Clock clock) {
        this.parties = parties;
        this.groups = groups;
        this.loader = loader;
        this.access = access;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /** A new party; {@code force} accepts a phone another party has (PTY-02). */
    public record CreateParty(
            PartyType type,
            String displayName,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String taxId,
            @Nullable Address address,
            @Nullable UUID customerGroupId,
            boolean force) {}

    /** PATCH semantics: {@code null} fields stay as they are. */
    public record UpdateParty(
            UUID partyId,
            long expectedVersion,
            @Nullable PartyType type,
            @Nullable String displayName,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String taxId,
            @Nullable Address address,
            @Nullable UUID customerGroupId,
            @Nullable PartyStatus status,
            boolean force) {}

    @Transactional
    @Observed(name = "bmp.party.create-party")
    public PartyView create(CreateParty command) {
        if (command.type() != PartyType.CUSTOMER) {
            access.requireWrite("suppliers");
        }
        if (command.force()) {
            access.requireWrite("force");
        }
        String phone = command.phone() == null ? null : normalised(command.phone(), null, command.force());
        if (command.customerGroupId() != null) {
            requireActiveGroup(command.customerGroupId());
        }
        Party saved = parties.saveAndFlush(Party.create(
                ids.newUuid(),
                TenantContext.require(),
                command.type(),
                command.displayName(),
                phone,
                command.email(),
                command.taxId(),
                command.address(),
                command.customerGroupId()));
        events.created(saved);
        log.atInfo()
                .addKeyValue("partyId", saved.id())
                .addKeyValue("type", saved.type())
                .log("Party created");
        return PartyView.of(saved);
    }

    @Transactional
    @Observed(name = "bmp.party.update-party")
    public PartyView update(UpdateParty command) {
        Party party = loader.party(command.partyId());
        IfMatch.check(command.expectedVersion(), party.version());
        if (command.type() != null) {
            party.changeType(command.type());
        }
        if (command.displayName() != null) {
            party.rename(command.displayName());
        }
        if (command.phone() != null) {
            party.changePhone(normalised(command.phone(), party.id(), command.force()));
        }
        if (command.email() != null) {
            party.changeEmail(command.email());
        }
        if (command.taxId() != null) {
            party.changeTaxId(command.taxId());
        }
        if (command.address() != null) {
            party.changeAddress(command.address());
        }
        if (command.customerGroupId() != null) {
            requireActiveGroup(command.customerGroupId());
            party.assignGroup(command.customerGroupId());
        }
        if (command.status() == PartyStatus.INACTIVE) {
            party.deactivate();
        } else if (command.status() == PartyStatus.ACTIVE) {
            party.activate();
        }
        return saved(party);
    }

    /** Rule PTY-03: new credit terms; the endpoint requires {@code party:credit}. */
    @Transactional
    @Observed(name = "bmp.party.update-credit-policy")
    public PartyView changeCreditPolicy(UUID partyId, long expectedVersion, CreditPolicy policy) {
        Party party = loader.party(partyId);
        IfMatch.check(expectedVersion, party.version());
        party.changeCreditPolicy(policy);
        return saved(party);
    }

    /** Rule PTY-04: replaces the personal data; repeating it changes nothing and publishes nothing. */
    @Transactional
    @Observed(name = "bmp.party.anonymise-party")
    public PartyView anonymise(UUID partyId) {
        Party party = loader.party(partyId);
        if (!party.anonymise(clock.instant())) {
            return PartyView.of(party);
        }
        PartyView view = saved(party);
        log.atInfo().addKeyValue("partyId", party.id()).log("Party anonymised");
        return view;
    }

    private PartyView saved(Party party) {
        Party saved = parties.saveAndFlush(party);
        events.updated(saved);
        return PartyView.of(saved);
    }

    /** Rule PTY-02: a phone another party of the tenant has is accepted only with {@code force}. */
    private String normalised(String typed, @Nullable UUID self, boolean force) {
        String phone = PhoneNumbers.normalise(typed, PhoneNumbers.DEFAULT_CALLING_CODE);
        Optional<Party> other = parties.findByPhone(phone).stream()
                .filter(p -> !p.id().equals(self))
                .findFirst();
        if (other.isPresent() && !force) {
            throw new BusinessRuleViolation(
                    PartyErrorCode.DUPLICATE_PARTY_PHONE,
                    "Another party has this phone",
                    Map.of("partyId", other.get().id()));
        }
        return phone;
    }

    private void requireActiveGroup(UUID groupId) {
        boolean active = groups.findById(groupId)
                .map(CustomerGroup::status)
                .filter(s -> s == PartyStatus.ACTIVE)
                .isPresent();
        if (!active) {
            throw new BusinessRuleViolation(PartyErrorCode.UNKNOWN_CUSTOMER_GROUP, Map.of("customerGroupId", groupId));
        }
    }
}
