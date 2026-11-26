package com.bmp.sales.adapter.out.persistence;

import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.CreditNote;
import com.bmp.sales.domain.model.Register;
import com.bmp.sales.domain.model.Shift;
import com.bmp.sales.domain.port.BillRepository;
import com.bmp.sales.domain.port.CreditNoteRepository;
import com.bmp.sales.domain.port.RegisterRepository;
import com.bmp.sales.domain.port.ShiftRepository;
import com.bmp.shared.context.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JPA stores of the sales aggregates; JPQL is built only from fixed fragments, values are bound. */
final class JpaSalesStores {

    private JpaSalesStores() {}

    @Repository
    static class Bills implements BillRepository {

        private final SpringDataBills bills;
        private final EntityManager em;
        private final JdbcClient jdbc;

        Bills(SpringDataBills bills, EntityManager em, JdbcClient jdbc) {
            this.bills = bills;
            this.em = em;
            this.jdbc = jdbc;
        }

        @Override
        public Optional<Bill> findById(UUID id) {
            return bills.findById(id);
        }

        @Override
        public Optional<Bill> lock(UUID id) {
            return Optional.ofNullable(em.find(Bill.class, id, LockModeType.PESSIMISTIC_WRITE));
        }

        @Override
        public Optional<Bill> findByNumber(String number) {
            return em.createQuery("select b from Bill b where b.number = :number", Bill.class)
                    .setParameter("number", number)
                    .getResultStream()
                    .findFirst();
        }

        @Override
        public List<Bill> findPage(Filter filter, @Nullable UUID beforeId, int limit) {
            StringBuilder jpql = new StringBuilder("select b from Bill b where 1 = 1");
            if (filter.status() != null) {
                jpql.append(" and b.status = :status");
            }
            if (filter.registerId() != null) {
                jpql.append(" and b.registerId = :registerId");
            }
            if (filter.customerId() != null) {
                jpql.append(" and b.customerId = :customerId");
            }
            if (filter.businessDate() != null) {
                jpql.append(" and b.businessDate = :businessDate");
            }
            if (filter.flaggedOnly()) {
                jpql.append(" and array_length(b.flags) > 0");
            }
            if (beforeId != null) {
                jpql.append(" and b.id < :beforeId");
            }
            TypedQuery<Bill> query =
                    em.createQuery(jpql.append(" order by b.id desc").toString(), Bill.class);
            if (filter.status() != null) {
                query.setParameter("status", filter.status());
            }
            if (filter.registerId() != null) {
                query.setParameter("registerId", filter.registerId());
            }
            if (filter.customerId() != null) {
                query.setParameter("customerId", filter.customerId());
            }
            if (filter.businessDate() != null) {
                query.setParameter("businessDate", filter.businessDate());
            }
            if (beforeId != null) {
                query.setParameter("beforeId", beforeId);
            }
            return query.setMaxResults(limit).getResultList();
        }

        @Override
        public BigDecimal creditExposure(UUID customerId) {
            return jdbc.sql("""
                            SELECT coalesce((SELECT sum(t.amount) FROM sales.bill_tender t
                                              JOIN sales.bill b ON b.tenant_id = t.tenant_id AND b.id = t.bill_id
                                             WHERE b.tenant_id = :tenantId AND b.customer_id = :customerId
                                               AND t.method = 'CREDIT'
                                               AND b.status IN ('COMPLETED', 'PARTIALLY_RETURNED', 'RETURNED')), 0)
                                 - coalesce((SELECT sum(r.amount) FROM sales.credit_note_refund r
                                              JOIN sales.credit_note n ON n.tenant_id = r.tenant_id
                                                                      AND n.id = r.credit_note_id
                                              JOIN sales.bill b ON b.tenant_id = n.tenant_id AND b.id = n.bill_id
                                             WHERE b.tenant_id = :tenantId AND b.customer_id = :customerId
                                               AND r.method = 'CREDIT'), 0)
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .param("customerId", customerId)
                    .query(BigDecimal.class)
                    .single();
        }

        @Override
        public List<String> numbersOf(UUID registerId) {
            return jdbc.sql("SELECT number FROM sales.bill WHERE tenant_id = :tenantId AND register_id = :registerId")
                    .param("tenantId", TenantContext.require().value())
                    .param("registerId", registerId)
                    .query((rs, row) -> Objects.requireNonNull(rs.getString("number")))
                    .list();
        }

        @Override
        public boolean anyBill() {
            return Boolean.TRUE.equals(jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM sales.bill WHERE tenant_id = :tenantId)
                            """)
                    .param("tenantId", TenantContext.require().value())
                    .query(Boolean.class)
                    .single());
        }

        @Override
        public Bill saveAndFlush(Bill bill) {
            return bills.saveAndFlush(bill);
        }
    }

    @Repository
    static class CreditNotes implements CreditNoteRepository {

        private final SpringDataCreditNotes notes;
        private final EntityManager em;

        CreditNotes(SpringDataCreditNotes notes, EntityManager em) {
            this.notes = notes;
            this.em = em;
        }

        @Override
        public Optional<CreditNote> findById(UUID id) {
            return notes.findById(id);
        }

        @Override
        public List<CreditNote> findPage(@Nullable UUID billId, @Nullable UUID beforeId, int limit) {
            StringBuilder jpql = new StringBuilder("select n from CreditNote n where 1 = 1");
            if (billId != null) {
                jpql.append(" and n.billId = :billId");
            }
            if (beforeId != null) {
                jpql.append(" and n.id < :beforeId");
            }
            TypedQuery<CreditNote> query =
                    em.createQuery(jpql.append(" order by n.id desc").toString(), CreditNote.class);
            if (billId != null) {
                query.setParameter("billId", billId);
            }
            if (beforeId != null) {
                query.setParameter("beforeId", beforeId);
            }
            return query.setMaxResults(limit).getResultList();
        }

        @Override
        public CreditNote saveAndFlush(CreditNote note) {
            return notes.saveAndFlush(note);
        }
    }

    @Repository
    static class Registers implements RegisterRepository {

        private final SpringDataRegisters registers;
        private final EntityManager em;

        Registers(SpringDataRegisters registers, EntityManager em) {
            this.registers = registers;
            this.em = em;
        }

        @Override
        public Optional<Register> findById(UUID id) {
            return registers.findById(id);
        }

        @Override
        public Optional<Register> lock(UUID id) {
            return Optional.ofNullable(em.find(Register.class, id, LockModeType.PESSIMISTIC_WRITE));
        }

        @Override
        public List<Register> findPage(@Nullable UUID locationId, @Nullable String afterCode, int limit) {
            StringBuilder jpql = new StringBuilder("select r from Register r where 1 = 1");
            if (locationId != null) {
                jpql.append(" and r.locationId = :locationId");
            }
            if (afterCode != null) {
                jpql.append(" and r.code > :afterCode");
            }
            TypedQuery<Register> query =
                    em.createQuery(jpql.append(" order by r.code").toString(), Register.class);
            if (locationId != null) {
                query.setParameter("locationId", locationId);
            }
            if (afterCode != null) {
                query.setParameter("afterCode", afterCode);
            }
            return query.setMaxResults(limit).getResultList();
        }

        @Override
        public Register saveAndFlush(Register register) {
            return registers.saveAndFlush(register);
        }
    }

    @Repository
    static class Shifts implements ShiftRepository {

        private final SpringDataShifts shifts;
        private final EntityManager em;

        Shifts(SpringDataShifts shifts, EntityManager em) {
            this.shifts = shifts;
            this.em = em;
        }

        @Override
        public Optional<Shift> findById(UUID id) {
            return shifts.findById(id);
        }

        @Override
        public Optional<Shift> findOpen(UUID registerId) {
            return em.createQuery(
                            "select s from Shift s where s.registerId = :r and s.status = 'OPEN' and s.offline = false",
                            Shift.class)
                    .setParameter("r", registerId)
                    .getResultStream()
                    .findFirst();
        }

        @Override
        public Optional<Shift> findCovering(UUID registerId, Instant at) {
            return em.createQuery("""
                            select s from Shift s where s.registerId = :r and s.offline = false and s.openedAt <= :at
                               and (s.closedAt is null or s.closedAt >= :at) order by s.openedAt desc""", Shift.class)
                    .setParameter("r", registerId)
                    .setParameter("at", at)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst();
        }

        @Override
        public Optional<Shift> findOffline(UUID registerId) {
            return em.createQuery("select s from Shift s where s.registerId = :r and s.offline = true", Shift.class)
                    .setParameter("r", registerId)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst();
        }

        @Override
        public Shift saveAndFlush(Shift shift) {
            return shifts.saveAndFlush(shift);
        }
    }
}
