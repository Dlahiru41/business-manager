package com.bmp.pricing.domain.model;

/** Resolution tiers of price lists, tried in this order (PRC-04). */
public enum ListScope {
    CUSTOMER,
    CUSTOMER_GROUP,
    CHANNEL,
    ANY
}
