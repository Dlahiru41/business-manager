package com.bmp.sales.adapter.out.persistence;

import com.bmp.sales.domain.model.CreditNote;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCreditNotes extends JpaRepository<CreditNote, UUID> {}
