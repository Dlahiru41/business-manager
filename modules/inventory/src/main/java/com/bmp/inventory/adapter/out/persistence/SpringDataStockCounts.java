package com.bmp.inventory.adapter.out.persistence;

import com.bmp.inventory.domain.model.StockCount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataStockCounts extends JpaRepository<StockCount, UUID> {}
