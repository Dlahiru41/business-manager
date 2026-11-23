package com.bmp.procurement.domain.port;

import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.ReceiptStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** GoodsReceipts of the current tenant; pages are newest first. */
public interface GoodsReceiptRepository {

    Optional<GoodsReceipt> findById(UUID id);

    List<GoodsReceipt> findPage(
            @Nullable ReceiptStatus status,
            @Nullable UUID supplierId,
            @Nullable UUID purchaseOrderId,
            @Nullable Boolean invoiced,
            @Nullable UUID beforeId,
            int limit);

    /** The receipts, locked until the transaction ends (matching them to an invoice). */
    List<GoodsReceipt> lockAll(Collection<UUID> ids);

    /** The posted returns that correct the receipt. */
    List<GoodsReceipt> findPostedCorrections(UUID receiptId);

    GoodsReceipt saveAndFlush(GoodsReceipt goodsReceipt);
}
