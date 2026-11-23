package com.bmp.procurement.domain.port;

import com.bmp.procurement.domain.model.InvoiceStatus;
import com.bmp.procurement.domain.model.SupplierInvoice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** SupplierInvoices of the current tenant; pages are newest first. */
public interface SupplierInvoiceRepository {

    Optional<SupplierInvoice> findById(UUID id);

    List<SupplierInvoice> findPage(
            @Nullable InvoiceStatus status, @Nullable UUID supplierId, @Nullable UUID beforeId, int limit);

    SupplierInvoice saveAndFlush(SupplierInvoice supplierInvoice);
}
