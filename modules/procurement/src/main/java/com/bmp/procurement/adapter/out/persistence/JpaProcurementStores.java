package com.bmp.procurement.adapter.out.persistence;

import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.InvoiceStatus;
import com.bmp.procurement.domain.model.OrderStatus;
import com.bmp.procurement.domain.model.PurchaseOrder;
import com.bmp.procurement.domain.model.ReceiptStatus;
import com.bmp.procurement.domain.model.SupplierInvoice;
import com.bmp.procurement.domain.port.GoodsReceiptRepository;
import com.bmp.procurement.domain.port.PurchaseOrderRepository;
import com.bmp.procurement.domain.port.SupplierInvoiceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** The procurement repositories on JPA; filters are added only when given, so no query binds an untyped null. */
final class JpaProcurementStores {

    private JpaProcurementStores() {}

    static <T> List<T> page(
            EntityManager em,
            Class<T> type,
            String entity,
            Map<String, @Nullable Object> filters,
            @Nullable UUID beforeId,
            int limit) {
        StringBuilder jpql = new StringBuilder("select e from " + entity + " e where 1 = 1");
        Map<String, Object> bound = new LinkedHashMap<>();
        filters.forEach((field, value) -> {
            if (value != null) {
                jpql.append(" and e.").append(field).append(" = :").append(field);
                bound.put(field, value);
            }
        });
        if (beforeId != null) {
            jpql.append(" and e.id < :beforeId");
            bound.put("beforeId", beforeId);
        }
        TypedQuery<T> query = em.createQuery(jpql.append(" order by e.id desc").toString(), type);
        bound.forEach(query::setParameter);
        return query.setMaxResults(limit).getResultList();
    }

    @Repository
    static class Orders implements PurchaseOrderRepository {

        private final SpringDataPurchaseOrders orders;
        private final EntityManager em;

        Orders(SpringDataPurchaseOrders orders, EntityManager em) {
            this.orders = orders;
            this.em = em;
        }

        @Override
        public Optional<PurchaseOrder> findById(UUID id) {
            return orders.findById(id);
        }

        @Override
        public List<PurchaseOrder> findPage(
                @Nullable OrderStatus status, @Nullable UUID supplierId, @Nullable UUID beforeId, int limit) {
            Map<String, @Nullable Object> filters = new LinkedHashMap<>();
            filters.put("status", status);
            filters.put("supplierId", supplierId);
            return page(em, PurchaseOrder.class, "PurchaseOrder", filters, beforeId, limit);
        }

        @Override
        public PurchaseOrder saveAndFlush(PurchaseOrder order) {
            return orders.saveAndFlush(order);
        }
    }

    @Repository
    static class Receipts implements GoodsReceiptRepository {

        private final SpringDataGoodsReceipts receipts;
        private final EntityManager em;

        Receipts(SpringDataGoodsReceipts receipts, EntityManager em) {
            this.receipts = receipts;
            this.em = em;
        }

        @Override
        public Optional<GoodsReceipt> findById(UUID id) {
            return receipts.findById(id);
        }

        @Override
        public List<GoodsReceipt> findPage(
                @Nullable ReceiptStatus status,
                @Nullable UUID supplierId,
                @Nullable UUID purchaseOrderId,
                @Nullable Boolean invoiced,
                @Nullable UUID beforeId,
                int limit) {
            Map<String, @Nullable Object> filters = new LinkedHashMap<>();
            filters.put("status", status);
            filters.put("supplierId", supplierId);
            filters.put("purchaseOrderId", purchaseOrderId);
            filters.put("invoiced", invoiced);
            return page(em, GoodsReceipt.class, "GoodsReceipt", filters, beforeId, limit);
        }

        @Override
        public List<GoodsReceipt> lockAll(Collection<UUID> ids) {
            List<GoodsReceipt> locked = new ArrayList<>();
            for (UUID id : new TreeSet<>(ids)) {
                GoodsReceipt receipt = em.find(GoodsReceipt.class, id, LockModeType.PESSIMISTIC_WRITE);
                if (receipt != null) {
                    locked.add(receipt);
                }
            }
            return locked;
        }

        @Override
        public List<GoodsReceipt> findPostedCorrections(UUID receiptId) {
            return em.createQuery(
                            "SELECT r FROM GoodsReceipt r WHERE r.correctsReceiptId = :id AND r.status = :status",
                            GoodsReceipt.class)
                    .setParameter("id", receiptId)
                    .setParameter("status", ReceiptStatus.POSTED)
                    .getResultList();
        }

        @Override
        public GoodsReceipt saveAndFlush(GoodsReceipt receipt) {
            return receipts.saveAndFlush(receipt);
        }
    }

    @Repository
    static class Invoices implements SupplierInvoiceRepository {

        private final SpringDataSupplierInvoices invoices;
        private final EntityManager em;

        Invoices(SpringDataSupplierInvoices invoices, EntityManager em) {
            this.invoices = invoices;
            this.em = em;
        }

        @Override
        public Optional<SupplierInvoice> findById(UUID id) {
            return invoices.findById(id);
        }

        @Override
        public List<SupplierInvoice> findPage(
                @Nullable InvoiceStatus status, @Nullable UUID supplierId, @Nullable UUID beforeId, int limit) {
            Map<String, @Nullable Object> filters = new LinkedHashMap<>();
            filters.put("status", status);
            filters.put("supplierId", supplierId);
            return page(em, SupplierInvoice.class, "SupplierInvoice", filters, beforeId, limit);
        }

        @Override
        public SupplierInvoice saveAndFlush(SupplierInvoice invoice) {
            return invoices.saveAndFlush(invoice);
        }
    }
}
