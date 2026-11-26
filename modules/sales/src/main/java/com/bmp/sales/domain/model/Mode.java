package com.bmp.sales.domain.model;

/**
 * How a bill arrived: online bills are refused when they break a rule; offline bills already happened at the till
 * (the customer paid) and are accepted with flags (SAL-03..SAL-07).
 */
public enum Mode {
    ONLINE,
    OFFLINE;

    public boolean online() {
        return this == ONLINE;
    }
}
