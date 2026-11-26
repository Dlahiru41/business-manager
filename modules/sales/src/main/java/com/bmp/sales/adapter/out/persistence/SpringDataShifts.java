package com.bmp.sales.adapter.out.persistence;

import com.bmp.sales.domain.model.Shift;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataShifts extends JpaRepository<Shift, UUID> {}
