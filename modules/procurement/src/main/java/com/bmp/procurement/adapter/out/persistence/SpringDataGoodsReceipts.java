package com.bmp.procurement.adapter.out.persistence;

import com.bmp.procurement.domain.model.GoodsReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataGoodsReceipts extends JpaRepository<GoodsReceipt, UUID> {}
