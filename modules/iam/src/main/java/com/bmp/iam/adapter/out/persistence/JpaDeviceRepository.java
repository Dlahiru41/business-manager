package com.bmp.iam.adapter.out.persistence;

import com.bmp.iam.domain.model.Device;
import com.bmp.iam.domain.port.DeviceRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of the corresponding port; RLS and {@code @TenantId} scope every query. */
interface JpaDeviceRepository extends JpaRepository<Device, UUID>, DeviceRepository {}
