package com.bmp.sync.domain.model;

/** What became of an uploaded record (SYN-04, SYN-05). */
public enum UploadStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED
}
