package com.bmp.printagent.printer;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;

/** A printer on a serial or USB-serial port (8 data bits, no parity, one stop bit). */
public final class SerialPrinter implements Printer {

    private static final int DATA_BITS = 8;

    private final String portName;
    private final int baudRate;
    private final int timeoutMillis;

    public SerialPrinter(String portName, int baudRate, int timeoutMillis) {
        this.portName = portName;
        this.baudRate = baudRate;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void print(byte[] document) throws PrinterException {
        SerialPort port = open();
        try {
            int written = port.writeBytes(document, document.length);
            if (written != document.length) {
                throw new PrinterException(
                        PrinterStatus.ERROR,
                        "The printer on " + portName + " took " + written + " of " + document.length + " bytes",
                        null);
            }
        } finally {
            port.closePort();
        }
    }

    @Override
    public PrinterStatus status() {
        try {
            open().closePort();
            return PrinterStatus.ONLINE;
        } catch (PrinterException e) {
            return e.status();
        }
    }

    private SerialPort open() throws PrinterException {
        SerialPort port;
        try {
            port = SerialPort.getCommPort(portName);
        } catch (SerialPortInvalidPortException e) {
            throw new PrinterException(PrinterStatus.OFFLINE, "No serial port " + portName, e);
        }
        port.setComPortParameters(baudRate, DATA_BITS, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, timeoutMillis);
        if (!port.openPort()) {
            throw new PrinterException(
                    PrinterStatus.OFFLINE, "The serial port " + portName + " cannot be opened", null);
        }
        return port;
    }
}
