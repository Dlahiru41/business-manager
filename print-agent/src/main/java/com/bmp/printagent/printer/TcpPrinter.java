package com.bmp.printagent.printer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** A network printer on raw TCP (port 9100): one connection per document. */
public final class TcpPrinter implements Printer {

    /** {@code DLE EOT 4}: the paper roll sensor's real-time status. */
    private static final byte[] PAPER_STATUS = {0x10, 0x04, 0x04};

    /** Bits 5 and 6 of the answer: the paper has run out. */
    private static final int PAPER_END = 0x60;

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public TcpPrinter(String host, int port, int timeoutMillis) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void print(byte[] document) throws PrinterException {
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            out.write(document);
            out.flush();
        } catch (IOException e) {
            throw new PrinterException(
                    PrinterStatus.OFFLINE, "The printer at " + host + ":" + port + " is unreachable", e);
        }
    }

    @Override
    public PrinterStatus status() {
        try (Socket socket = connect()) {
            socket.getOutputStream().write(PAPER_STATUS);
            socket.getOutputStream().flush();
            return paper(socket.getInputStream().read());
        } catch (SocketTimeoutException e) {
            return PrinterStatus.ONLINE; // reachable, but without real-time status
        } catch (IOException e) {
            return PrinterStatus.OFFLINE;
        }
    }

    /** The paper sensor's answer, or -1 when the printer closed the connection without one. */
    public static PrinterStatus paper(int answer) {
        return answer >= 0 && (answer & PAPER_END) != 0 ? PrinterStatus.PAPER_OUT : PrinterStatus.ONLINE;
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }
}
