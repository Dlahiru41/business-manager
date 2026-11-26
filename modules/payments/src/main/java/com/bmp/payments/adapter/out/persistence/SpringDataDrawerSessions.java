package com.bmp.payments.adapter.out.persistence;

import com.bmp.payments.domain.model.DrawerSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDrawerSessions extends JpaRepository<DrawerSession, UUID> {}
