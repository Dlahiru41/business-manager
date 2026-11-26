package com.bmp.sales.application;

import com.bmp.sales.api.SalesQueries;
import com.bmp.sales.api.dto.BillReceipt;
import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.sales.domain.model.BillStatus;
import com.bmp.sales.domain.model.Register;
import com.bmp.sales.domain.port.BillRepository;
import com.bmp.sales.domain.port.CustomerStore;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.sales.domain.port.SaleItemStore;
import com.bmp.sales.domain.service.BillNumbers;
import com.bmp.shared.id.RegisterId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link SalesQueries} for payments, sync and documents. */
@Component
class SalesQueriesImpl implements SalesQueries {

    private final RegisterRepository registers;
    private final BillRepository bills;
    private final SaleItemStore items;
    private final CustomerStore customers;

    SalesQueriesImpl(RegisterRepository registers, BillRepository bills, SaleItemStore items, CustomerStore customers) {
        this.registers = registers;
        this.bills = bills;
        this.items = items;
        this.customers = customers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillReceipt> findReceipt(UUID billId) {
        return bills.findById(billId)
                .filter(b -> b.status() != BillStatus.DRAFT && b.status() != BillStatus.VOIDED)
                .map(b -> new BillReceipt(
                        b.id(),
                        b.number(),
                        b.locationId(),
                        registers.findById(b.registerId()).map(Register::code).orElse(""),
                        Objects.requireNonNull(b.completedAt()),
                        b.lines().stream()
                                .map(l -> new BillReceipt.Line(
                                        l.description(),
                                        l.qty(),
                                        items.find(l.variantId())
                                                .flatMap(i -> i.unit(l.packagingUnitId()))
                                                .map(SaleItemStore.Unit::name)
                                                .orElse(""),
                                        l.unitPrice(),
                                        l.lineTotal(),
                                        l.lineDiscount()))
                                .toList(),
                        b.payableTotals().discountTotal(),
                        b.payableTotals().taxTotal(),
                        b.payableTotals().grandTotal(),
                        b.tenders().stream()
                                .map(t -> new BillReceipt.Tender(
                                        t.method().name(),
                                        t.amount(),
                                        t.changeGiven() == null ? BigDecimal.ZERO : t.changeGiven()))
                                .toList(),
                        b.creditAmount().signum() > 0 && b.customerId() != null
                                ? customers
                                        .find(Objects.requireNonNull(b.customerId()))
                                        .map(CustomerStore.Customer::displayName)
                                        .orElse(null)
                                : null,
                        b.currency()));
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
