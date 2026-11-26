package com.bmp.sales.application;

import com.bmp.sales.api.SalesQueries;
import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.shared.id.RegisterId;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link SalesQueries} for payments, sync and documents. */
@Component
class SalesQueriesImpl implements SalesQueries {

    private final RegisterRepository registers;

    SalesQueriesImpl(RegisterRepository registers) {
        this.registers = registers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegisterInfo> findRegister(RegisterId register) {
        return registers
                .findById(register.value())
                .map(r -> new RegisterInfo(
                        register,
                        r.code(),
                        r.locationId(),
                        r.deviceId(),
                        r.billPrefix(),
                        r.lastNumber(),
                        r.isActive()));
    }
}
