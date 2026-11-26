package com.bmp.sales.application.query;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillStatus;
import com.bmp.sales.domain.model.CreditNote;
import com.bmp.sales.domain.model.Register;
import com.bmp.sales.domain.port.BillRepository;
import com.bmp.sales.domain.port.CreditNoteRepository;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pages and reads of registers, bills and credit notes; lines loaded inside the transaction. */
@Service
public class SalesQueryService {

    private final BillRepository bills;
    private final CreditNoteRepository notes;
    private final RegisterRepository registers;

    public SalesQueryService(BillRepository bills, CreditNoteRepository notes, RegisterRepository registers) {
        this.bills = bills;
        this.notes = notes;
        this.registers = registers;
    }

    /** What a bill list is narrowed to; {@code null} means any. */
    public record BillSearch(
            @Nullable BillStatus status,
            @Nullable UUID registerId,
            @Nullable UUID customerId,
            @Nullable LocalDate businessDate,
            boolean flaggedOnly) {}

    @Transactional(readOnly = true)
    public PageResponse<Bill> bills(BillSearch search, PageQuery query) {
        BillRepository.Filter filter = new BillRepository.Filter(
                search.status(), search.registerId(), search.customerId(), search.businessDate(), search.flaggedOnly());
        List<Bill> page = bills.findPage(filter, before(query), query.fetchSize());
        page.forEach(b -> {
            b.lines();
            b.tenders();
        });
        return PageResponse.of(page, query, b -> List.of(b.id().toString()));
    }

    @Transactional(readOnly = true)
    public Bill bill(UUID id) {
        Bill bill = bills.findById(id)
                .orElseThrow(() -> new NotFoundException(SalesErrorCode.BILL_NOT_FOUND, Map.of("billId", id)));
        bill.lines();
        bill.tenders();
        return bill;
    }

    @Transactional(readOnly = true)
    public PageResponse<CreditNote> creditNotes(@Nullable UUID billId, PageQuery query) {
        List<CreditNote> page = notes.findPage(billId, before(query), query.fetchSize());
        page.forEach(n -> {
            n.lines();
            n.refundTenders();
        });
        return PageResponse.of(page, query, n -> List.of(n.id().toString()));
    }

    @Transactional(readOnly = true)
    public CreditNote creditNote(UUID id) {
        CreditNote note = notes.findById(id)
                .orElseThrow(
                        () -> new NotFoundException(SalesErrorCode.CREDIT_NOTE_NOT_FOUND, Map.of("creditNoteId", id)));
        note.lines();
        note.refundTenders();
        return note;
    }

    @Transactional(readOnly = true)
    public PageResponse<Register> registers(@Nullable UUID locationId, PageQuery query) {
        String after = query.cursor() == null ? null : decode(query).getFirst();
        return PageResponse.of(registers.findPage(locationId, after, query.fetchSize()), query, r -> List.of(r.code()));
    }

    @Transactional(readOnly = true)
    public Register register(UUID id) {
        return registers
                .findById(id)
                .orElseThrow(() -> new NotFoundException(SalesErrorCode.REGISTER_NOT_FOUND, Map.of("registerId", id)));
    }

    private static @Nullable UUID before(PageQuery query) {
        if (query.cursor() == null) {
            return null;
        }
        try {
            return UUID.fromString(decode(query).getFirst());
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private static List<String> decode(PageQuery query) {
        try {
            return CursorCodec.decode(java.util.Objects.requireNonNull(query.cursor()), 1);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private static BusinessRuleViolation invalidCursor() {
        return new BusinessRuleViolation(
                PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
    }
}
