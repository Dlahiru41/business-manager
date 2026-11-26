package com.bmp.printagent.printer;

/** What the printer reports; the same values as documents.yaml {@code PrintAgentStatus}. */
public enum PrinterStatus {
    ONLINE,
    OFFLINE,
    PAPER_OUT,
    ERROR
}
