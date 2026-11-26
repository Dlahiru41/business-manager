package com.bmp.payments.domain.model;

/** A drawer session's life (PAY-04): cash moves while OPEN, is counted (again, if need be), then reconciled. */
public enum DrawerStatus {
    OPEN,
    COUNTED,
    RECONCILED
}
