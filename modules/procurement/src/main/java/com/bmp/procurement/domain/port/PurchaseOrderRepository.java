package com.bmp.procurement.domain.port;

import com.bmp.procurement.domain.model.OrderStatus;
import com.bmp.procurement.domain.model.PurchaseOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** PurchaseOrders of the current tenant; pages are newest first. */
public interface PurchaseOrderRepository {

    Optional<PurchaseOrder> findById(UUID id);

    /** The order, locked until the transaction ends (posting a receipt against it). */
    Optional<PurchaseOrder> lock(UUID id);

    List<PurchaseOrder> findPage(
            @Nullable OrderStatus status, @Nullable UUID supplierId, @Nullable UUID beforeId, int limit);

    PurchaseOrder saveAndFlush(PurchaseOrder purchaseOrder);
}
