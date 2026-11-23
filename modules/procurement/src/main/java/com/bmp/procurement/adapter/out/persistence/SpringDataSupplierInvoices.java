package com.bmp.procurement.adapter.out.persistence;

import com.bmp.procurement.domain.model.SupplierInvoice;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSupplierInvoices extends JpaRepository<SupplierInvoice, UUID> {}
