package com.bmp.web.testapp;

/** A printer stream the dummy controller answers: bytes that are not text (NUL, ESC, 0xFF). */
public final class PrinterStream {

    private PrinterStream() {}

    public static byte[] bytes() {
        return new byte[] {0x1b, 0x40, 0x00, (byte) 0xff, 0x0a, 0x1d, 0x56, 0x42, 0x00};
    }
}
