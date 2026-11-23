package com.bmp.party.domain.model;

/** What a party is to the business (PTY-01). */
public enum PartyType {
    CUSTOMER,
    SUPPLIER,
    BOTH;

    public boolean isCustomer() {
        return this != SUPPLIER;
    }

    public boolean isSupplier() {
        return this != CUSTOMER;
    }
}
