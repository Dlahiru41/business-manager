package com.bmp.sales.application.command;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.Register;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.LocationId;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.web.etag.IfMatch;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers: a code, a location, a device and the bill number prefix (SAL-01, SAL-12). */
@Service
public class RegisterHandlers {

    private final RegisterRepository registers;
    private final TenantQueries tenants;
    private final IdGenerator ids;

    public RegisterHandlers(RegisterRepository registers, TenantQueries tenants, IdGenerator ids) {
        this.registers = registers;
        this.tenants = tenants;
        this.ids = ids;
    }

    @Transactional
    public Register create(
            String code, @Nullable String name, UUID locationId, @Nullable UUID deviceId, String prefix) {
        if (!tenants.isActiveLocation(LocationId.of(locationId))) {
            throw new BusinessRuleViolation(SalesErrorCode.UNKNOWN_LOCATION, Map.of("locationId", locationId));
        }
        return registers.saveAndFlush(
                Register.create(ids.newUuid(), TenantContext.require(), code, name, locationId, deviceId, prefix));
    }

    @Transactional
    @SuppressWarnings("TooManyParameters") // a patch of the register's settings
    public Register update(
            UUID id,
            long expectedVersion,
            @Nullable String name,
            @Nullable UUID deviceId,
            @Nullable String prefix,
            @Nullable String status) {
        Register register = registers
                .findById(id)
                .orElseThrow(() -> new NotFoundException(SalesErrorCode.REGISTER_NOT_FOUND, Map.of("registerId", id)));
        IfMatch.check(expectedVersion, register.version());
        if (name != null) {
            register.rename(name);
        }
        if (deviceId != null) {
            register.bindDevice(deviceId);
        }
        if (prefix != null) {
            register.changePrefix(prefix);
        }
        if (status != null) {
            register.changeStatus(status);
        }
        return registers.saveAndFlush(register);
    }
}
