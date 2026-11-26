package com.bmp.sales.application.command;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.Register;
import com.bmp.sales.domain.model.Shift;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.sales.domain.port.ShiftRepository;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shifts on registers (SAL-12): one OPEN shift per register; offline bills fall back to a synthetic shift. */
@Service
public class ShiftHandlers {

    private static final Logger log = LoggerFactory.getLogger(ShiftHandlers.class);

    private final ShiftRepository shifts;
    private final RegisterRepository registers;
    private final IdGenerator ids;
    private final Clock clock;

    public ShiftHandlers(ShiftRepository shifts, RegisterRepository registers, IdGenerator ids, Clock clock) {
        this.shifts = shifts;
        this.registers = registers;
        this.ids = ids;
        this.clock = clock;
    }

    /** Opens a shift for the caller; an id seen before replays (a till retrying). */
    @Transactional
    public Shift open(@Nullable UUID id, UUID registerId, @Nullable Instant openedAt, @Nullable UUID cashierId) {
        if (id != null) {
            Optional<Shift> known = shifts.findById(id);
            if (known.isPresent()) {
                return known.get();
            }
        }
        Register register = activeRegister(registerId);
        if (shifts.findOpen(register.id()).isPresent()) {
            throw new BusinessRuleViolation(SalesErrorCode.SHIFT_ALREADY_OPEN, Map.of("registerId", registerId));
        }
        UUID cashier = cashierId != null ? cashierId : currentUser();
        Shift shift = shifts.saveAndFlush(Shift.open(
                id != null ? id : ids.newUuid(),
                TenantContext.require(),
                register.id(),
                cashier,
                openedAt != null ? openedAt : clock.instant(),
                false));
        log.atInfo()
                .addKeyValue("shiftId", shift.id())
                .addKeyValue("registerId", registerId)
                .log("Shift opened");
        return shift;
    }

    @Transactional
    public Shift close(UUID shiftId, @Nullable Instant closedAt) {
        Shift shift = shifts.findById(shiftId)
                .orElseThrow(() -> new NotFoundException(SalesErrorCode.SHIFT_NOT_FOUND, Map.of("shiftId", shiftId)));
        shift.close(closedAt != null ? closedAt : clock.instant());
        log.atInfo().addKeyValue("shiftId", shiftId).log("Shift closed");
        return shifts.saveAndFlush(shift);
    }

    /** Whether the shift exists and is closed (a till resending its close). */
    @Transactional(readOnly = true)
    public boolean isClosed(UUID shiftId) {
        return shifts.findById(shiftId).map(s -> !s.isOpen()).orElse(false);
    }

    /** Rule SAL-12: the register's synthetic offline shift, created when first needed. */
    @Transactional
    public Shift offlineShift(UUID registerId, Instant at) {
        return shifts.findOffline(registerId)
                .orElseGet(() -> shifts.saveAndFlush(
                        Shift.open(ids.newUuid(), TenantContext.require(), registerId, null, at, true)));
    }

    Register activeRegister(UUID registerId) {
        return registers
                .findById(registerId)
                .filter(Register::isActive)
                .orElseThrow(() ->
                        new BusinessRuleViolation(SalesErrorCode.UNKNOWN_REGISTER, Map.of("registerId", registerId)));
    }

    private static @Nullable UUID currentUser() {
        var user = ActorContext.currentOrSystem().userId();
        return user == null ? null : user.value();
    }
}
