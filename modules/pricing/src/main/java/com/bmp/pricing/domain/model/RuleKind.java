package com.bmp.pricing.domain.model;

/** Kinds of price rules (PRC-06); at most one non-stackable PROMOTION applies per line. */
public enum RuleKind {
    QTY_BREAK,
    CUSTOMER_GROUP,
    PROMOTION,
    MARKUP
}
