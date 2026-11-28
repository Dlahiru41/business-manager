package com.bmp.finance.domain.model;

/** Accounts are never deleted; an inactive one takes no manual lines. */
public enum AccountStatus {
    ACTIVE,
    INACTIVE
}
