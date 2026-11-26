package com.bmp.sales.adapter.out.persistence;

import com.bmp.sales.domain.model.Bill;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBills extends JpaRepository<Bill, UUID> {}
