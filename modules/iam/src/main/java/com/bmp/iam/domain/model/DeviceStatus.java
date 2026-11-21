package com.bmp.iam.domain.model;

/** Lifecycle of a device. Revocation is final: a revoked till is registered again as a new device. */
public enum DeviceStatus {
    ACTIVE,
    REVOKED
}
