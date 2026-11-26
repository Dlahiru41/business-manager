package com.bmp.sales.adapter.out.persistence;

import com.bmp.sales.domain.model.Register;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRegisters extends JpaRepository<Register, UUID> {}
