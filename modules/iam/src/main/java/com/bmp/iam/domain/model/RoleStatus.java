package com.bmp.iam.domain.model;

/** Lifecycle of a role. Inactive roles grant nothing; system roles are always active (IAM-02). */
public enum RoleStatus {
    ACTIVE,
    INACTIVE
}
