package com.bmp.iam.domain.model;

/** Lifecycle of a user. Disabled users cannot sign in (the identity provider account is disabled too). */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
