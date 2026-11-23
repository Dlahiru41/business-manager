package com.bmp.party.adapter.out.persistence;

import com.bmp.party.domain.model.CustomerGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataCustomerGroups extends JpaRepository<CustomerGroup, UUID> {}
