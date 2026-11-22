package com.bmp.persistence.it;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WidgetRepository extends JpaRepository<Widget, UUID> {}
