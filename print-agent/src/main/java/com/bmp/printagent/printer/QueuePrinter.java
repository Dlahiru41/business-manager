package com.bmp.printagent.printer;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import javax.print.DocFlavor;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;

/** A printer behind an operating system print queue (USB printers), fed raw bytes through {@code javax.print}. */
public final class QueuePrinter implements Printer {

    private final String queueName;

    public QueuePrinter(String queueName) {
        this.queueName = queueName;
    }

    @Override
    public void print(byte[] document) throws PrinterException {
        PrintService service = service()
                .orElseThrow(
                        () -> new PrinterException(PrinterStatus.OFFLINE, "No print queue named " + queueName, null));
        try {
            service.createPrintJob()
                    .print(
                            new SimpleDoc(document, DocFlavor.BYTE_ARRAY.AUTOSENSE, null),
                            new HashPrintRequestAttributeSet());
        } catch (PrintException e) {
            throw new PrinterException(PrinterStatus.ERROR, "The print queue " + queueName + " refused the job", e);
        }
    }

    @Override
    public PrinterStatus status() {
        return service().isPresent() ? PrinterStatus.ONLINE : PrinterStatus.OFFLINE;
    }

    private Optional<PrintService> service() {
        String wanted = queueName.toLowerCase(Locale.ROOT);
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(s -> s.getName().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }
}
