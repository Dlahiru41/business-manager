package com.bmp.procurement.adapter.out.persistence;

import com.bmp.procurement.domain.model.PurchaseOrder;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataPurchaseOrders extends JpaRepository<PurchaseOrder, UUID> {}
