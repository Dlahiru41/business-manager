package com.bmp.party.adapter.out.persistence;

import com.bmp.party.domain.model.Party;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataParties extends JpaRepository<Party, UUID> {

    List<Party> findByPhone(String phone);
}
