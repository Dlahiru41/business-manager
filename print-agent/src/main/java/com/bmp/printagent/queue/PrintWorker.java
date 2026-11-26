package com.bmp.printagent.queue;

import com.bmp.printagent.config.AgentProperties;
import com.bmp.printagent.printer.Printer;
import com.bmp.printagent.printer.PrinterException;
import com.bmp.printagent.printer.PrinterStatus;
import com.bmp.printagent.queue.PrintQueue.Request;
import com.bmp.printagent.queue.PrintQueue.Source;
import com.bmp.receipt.ReceiptRenderer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prints the queue oldest first, one document at a time. A request that cannot be printed waits with back-off and
 * holds the ones behind it, so receipts come out in the order they were asked for. The till's own requests are kept
 * until printed; a reprint from the cloud is given up on after {@code agent.queue.cloud-max-attempts}.
 */
@Component
public class PrintWorker {

    private static final Logger log = LoggerFactory.getLogger(PrintWorker.class);
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

    private final PrintQueue queue;
    private final Printer printer;
    private final Clock clock;
    private final AgentProperties.Queue settings;
    private final ReentrantLock printing = new ReentrantLock();

    public PrintWorker(PrintQueue queue, Printer printer, Clock clock, AgentProperties settings) {
        this.queue = queue;
        this.printer = printer;
        this.clock = clock;
        this.settings = settings.queue();
    }

    /** Prints a request just queued, after the ones before it; the oldest is tried now even while it waits. */
    public Request print(UUID id) {
        drain(true);
        return queue.find(id).orElseThrow();
    }

    /** Opens the cash drawer now, between two documents; never queued (DOC-04). */
    public void openDrawer() throws PrinterException {
        printing.lock();
        try {
            printer.print(ReceiptRenderer.drawerKick());
            log.atInfo().log("Cash drawer opened");
        } finally {
            printing.unlock();
        }
    }

    @Scheduled(fixedDelayString = "${agent.queue.poll-interval}")
    public void drainDue() {
        drain(false);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purge() {
        int purged = queue.purge(clock.instant().minus(settings.retention()));
        if (purged > 0) {
            log.atInfo().addKeyValue("purged", purged).log("Old print requests removed");
        }
    }

    void drain(boolean now) {
        printing.lock();
        try {
            boolean first = true;
            for (Optional<Request> head = queue.head(); head.isPresent(); head = queue.head()) {
                Request request = head.get();
                boolean due = !request.nextAttemptAt().isAfter(clock.instant());
                boolean tryNow = due || (now && first);
                if (!tryNow || !attempt(request)) {
                    return;
                }
                first = false;
            }
        } finally {
            printing.unlock();
        }
    }

    /** One attempt; true when the queue may go on with the next request. */
    private boolean attempt(Request request) {
        int attempts = request.attempts() + 1;
        try {
            printer.print(queue.document(request.id()));
            queue.printed(request.id(), attempts, PrinterStatus.ONLINE);
            log.atInfo()
                    .addKeyValue("printRequestId", request.id())
                    .addKeyValue("source", request.source())
                    .addKeyValue("attempts", attempts)
                    .log("Printed");
            return true;
        } catch (PrinterException e) {
            String error = e.getMessage() == null ? e.status().name() : e.getMessage();
            if (request.source() == Source.CLOUD && attempts >= settings.cloudMaxAttempts()) {
                queue.failed(request.id(), attempts, error, e.status());
                log.atWarn()
                        .addKeyValue("printRequestId", request.id())
                        .addKeyValue("attempts", attempts)
                        .addKeyValue("printerStatus", e.status())
                        .log("Print job given up");
                return true;
            }
            Instant next = clock.instant().plus(backoff(attempts, settings.maxBackoff()));
            queue.retryLater(request.id(), attempts, error, next);
            log.atWarn()
                    .addKeyValue("printRequestId", request.id())
                    .addKeyValue("attempts", attempts)
                    .addKeyValue("printerStatus", e.status())
                    .log("Print failed, will retry");
            return false;
        }
    }

    /** One second, doubling with every attempt, at most {@code max}. */
    static Duration backoff(int attempts, Duration max) {
        Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts - 1, 20));
        return wait.compareTo(max) > 0 ? max : wait;
    }
}
