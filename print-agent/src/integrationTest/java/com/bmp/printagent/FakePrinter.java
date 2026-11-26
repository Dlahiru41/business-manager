package com.bmp.printagent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A network receipt printer on a local port: keeps the bytes of every connection as one document, and answers the
 * paper sensor query ({@code DLE EOT 4}) like an Epson printer. It can be switched off and on again on the same port.
 */
final class FakePrinter implements AutoCloseable {

    private static final byte[] PAPER_QUERY = {0x10, 0x04, 0x04};
    private static final int PAPER_OK = 0x12;
    private static final int PAPER_END = 0x72;

    private final int port;
    private final List<byte[]> documents = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private volatile boolean paperOut;

    private FakePrinter(ServerSocket server) {
        this.server = server;
        this.port = server.getLocalPort();
        listen(server);
    }

    static FakePrinter start() {
        try {
            return new FakePrinter(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int port() {
        return port;
    }

    List<byte[]> documents() {
        return List.copyOf(documents);
    }

    /** The documents as text (ESC/POS commands included), for {@code contains} checks. */
    List<String> texts() {
        return documents().stream()
                .map(d -> new String(d, StandardCharsets.ISO_8859_1))
                .toList();
    }

    void clear() {
        documents.clear();
    }

    void paperOut(boolean out) {
        this.paperOut = out;
    }

    /** Switched off: connections are refused. */
    void off() {
        try {
            server.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Switched on again, on the same port; nothing happens when it is on. */
    void on() {
        if (!server.isClosed()) {
            return;
        }
        try {
            ServerSocket again = new ServerSocket();
            again.setReuseAddress(true);
            again.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
            server = again;
            listen(again);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        off();
    }

    private void listen(ServerSocket socket) {
        Thread.ofVirtual().name("fake-printer").start(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread.ofVirtual().start(() -> receive(client));
                } catch (IOException e) {
                    return; // switched off
                }
            }
        });
    }

    private void receive(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            ByteArrayOutputStream document = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n > 0; n = in.read(chunk)) {
                document.write(chunk, 0, n);
                if (Arrays.equals(document.toByteArray(), PAPER_QUERY)) {
                    client.getOutputStream().write(paperOut ? PAPER_END : PAPER_OK);
                    client.getOutputStream().flush();
                    document.reset();
                }
            }
            if (document.size() > 0) {
                documents.add(document.toByteArray());
            }
        } catch (IOException e) {
            // the agent hung up; what arrived is kept above
        }
    }
}
