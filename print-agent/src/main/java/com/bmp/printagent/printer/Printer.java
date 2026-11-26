package com.bmp.printagent.printer;

import com.bmp.printagent.config.AgentProperties;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A receipt printer that takes ESC/POS bytes. */
public interface Printer {

    /** Sends a whole document; returns once the printer has taken it. */
    void print(byte[] document) throws PrinterException;

    /** Asks the printer how it is, without printing. */
    PrinterStatus status();

    /** The printer {@code agent.printer} describes. */
    static Printer of(AgentProperties.Printer settings) {
        int timeout = Math.toIntExact(settings.timeout().toMillis());
        return switch (settings.type()) {
            case TCP -> new TcpPrinter(required(settings.host(), "agent.printer.host"), settings.port(), timeout);
            case QUEUE -> new QueuePrinter(required(settings.queueName(), "agent.printer.queue-name"));
            case SERIAL ->
                new SerialPrinter(
                        required(settings.serialPort(), "agent.printer.serial-port"), settings.baudRate(), timeout);
        };
    }

    private static String required(@Nullable String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set for this printer type");
        }
        return Objects.requireNonNull(value);
    }
}
