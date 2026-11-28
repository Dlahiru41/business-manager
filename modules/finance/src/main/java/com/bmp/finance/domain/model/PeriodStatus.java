package com.bmp.finance.domain.model;

/** OPEN takes postings; CLOSING (a close running) and CLOSED send them to the next open period (FIN-05). */
public enum PeriodStatus {
    OPEN,
    CLOSING,
    CLOSED
}
