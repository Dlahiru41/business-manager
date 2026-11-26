package com.bmp.printagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.printagent.config.AgentProperties;
import com.bmp.printagent.config.AgentProperties.PrinterType;
import com.bmp.printagent.printer.Printer;
import com.bmp.printagent.printer.PrinterException;
import com.bmp.printagent.printer.PrinterStatus;
import com.bmp.printagent.printer.QueuePrinter;
import com.bmp.printagent.printer.SerialPrinter;
import com.bmp.printagent.printer.TcpPrinter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PrintersTest {

    private static AgentProperties.Printer settings(PrinterType type, String host, String queue, String serial) {
        return new AgentProperties.Printer(type, host, 9100, queue, serial, 9600, Duration.ofMillis(300));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void agentYamlNamesThePrinterAndWhatEachTypeNeeds() {
        assertThat(Printer.of(settings(PrinterType.TCP, "127.0.0.1", null, null)))
                .isInstanceOf(TcpPrinter.class);
        assertThat(Printer.of(settings(PrinterType.QUEUE, null, "Receipt", null)))
                .isInstanceOf(QueuePrinter.class);
        assertThat(Printer.of(settings(PrinterType.SERIAL, null, null, "COM3"))).isInstanceOf(SerialPrinter.class);
        assertThatThrownBy(() -> Printer.of(settings(PrinterType.TCP, " ", null, null)))
                .hasMessageContaining("agent.printer.host");
        assertThatThrownBy(() -> Printer.of(settings(PrinterType.QUEUE, null, null, null)))
                .hasMessageContaining("agent.printer.queue-name");
        assertThatThrownBy(() -> Printer.of(settings(PrinterType.SERIAL, null, null, null)))
                .hasMessageContaining("agent.printer.serial-port");
    }

    @Test
    void aNetworkPrinterThatIsOffIsOfflineAndTakesNothing() throws IOException {
        TcpPrinter printer = new TcpPrinter("127.0.0.1", closedPort(), 300);

        assertThat(printer.status()).isEqualTo(PrinterStatus.OFFLINE);
        assertThatThrownBy(() -> printer.print(new byte[] {0x1b, 0x40}))
                .isInstanceOf(PrinterException.class)
                .satisfies(e -> assertThat(((PrinterException) e).status()).isEqualTo(PrinterStatus.OFFLINE));
    }

    @Test
    void thePaperSensorsAnswerSaysWhenThePaperRanOut() {
        assertThat(TcpPrinter.paper(0x12)).isEqualTo(PrinterStatus.ONLINE);
        assertThat(TcpPrinter.paper(0x72)).isEqualTo(PrinterStatus.PAPER_OUT);
        assertThat(TcpPrinter.paper(-1)).as("no answer").isEqualTo(PrinterStatus.ONLINE);
    }

    @Test
    void aPrintQueueOrSerialPortThatDoesNotExistIsOffline() {
        QueuePrinter queue = new QueuePrinter("No such printer " + System.nanoTime());
        assertThat(queue.status()).isEqualTo(PrinterStatus.OFFLINE);
        assertThatThrownBy(() -> queue.print(new byte[] {0x1b, 0x40}))
                .isInstanceOf(PrinterException.class)
                .hasMessageContaining("No print queue");

        SerialPrinter serial = new SerialPrinter("NOPORT" + System.nanoTime(), 9600, 300);
        assertThat(serial.status()).isEqualTo(PrinterStatus.OFFLINE);
        assertThatThrownBy(() -> serial.print(new byte[] {0x1b, 0x40})).isInstanceOf(PrinterException.class);
    }
}
