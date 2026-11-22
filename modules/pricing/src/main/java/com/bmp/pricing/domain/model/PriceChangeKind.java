package com.bmp.pricing.domain.model;

/** What a price upsert did to a price (PRC-03 change summary). */
public enum PriceChangeKind {
    CREATED,
    UPDATED,
    ENDED
}
