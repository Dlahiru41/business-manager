package com.bmp.sync.domain.model;

/** What a till did offline, by kind (SYN-04). */
public enum UploadRecordType {
    SHIFT_OPEN,
    DRAWER_OPEN,
    BILL,
    VOID,
    RETURN,
    CASH_MOVEMENT,
    DRAWER_COUNT,
    SHIFT_CLOSE,
    WASTAGE
}
