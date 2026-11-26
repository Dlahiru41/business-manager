package com.bmp.sales.application;

import com.bmp.sales.api.SalesQueries;
import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.sales.domain.port.BillRepository;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.sales.domain.service.BillNumbers;
import com.bmp.shared.id.RegisterId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link SalesQueries} for payments, sync and documents. */
@Component
class SalesQueriesImpl implements SalesQueries {

    private final RegisterRepository registers;
    private final BillRepository bills;

    SalesQueriesImpl(RegisterRepository registers, BillRepository bills) {
        this.registers = registers;
        this.bills = bills;
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

    @Override
    @Transactional(readOnly = true)
    public List<Long> billSequences(RegisterId register) {
        return registers
                .findById(register.value())
                .map(r -> bills.numbersOf(r.id()).stream()
                        .map(BillNumbers::parse)
                        .flatMap(Optional::stream)
                        .filter(n -> n.prefix().equals(r.billPrefix()))
                        .map(BillNumbers.BillNumber::sequence)
                        .sorted()
                        .toList())
                .orElse(List.of());
    }
}
