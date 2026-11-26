package com.bmp.printagent.cloud;

import com.bmp.printagent.config.AgentProperties;
import com.bmp.printagent.queue.PrintQueue;
import com.bmp.printagent.queue.PrintQueue.Request;
import com.bmp.printagent.queue.PrintWorker;
import com.bmp.printagent.receipt.ReceiptJson;
import com.bmp.printagent.receipt.TemplateCache;
import com.bmp.receipt.ReceiptRenderer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The agent's link to the cloud while it is reachable (DOC-05, DOC-06), on a thread of its own: results first, then
 * the template when it is due or a job names a newer one, then one long poll for the next job. A job is queued once
 * (by its id) and printed by the worker; its result is sent until the cloud has taken it. While the cloud is away the
 * link waits with back-off and the till's printing goes on.
 */
@Component
public class CloudLink implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CloudLink.class);
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(500);

    /** Where the link stands. */
    public enum State {
        DISABLED,
        CONNECTING,
        ONLINE,
        OFFLINE
    }

    private final AgentProperties.Cloud settings;
    private final ObjectProvider<CloudClient> clients;
    private final PrintQueue queue;
    private final PrintWorker worker;
    private final TemplateCache templates;
    private final ReceiptRenderer renderer;
    private final Clock clock;

    private volatile State state;
    private volatile @Nullable Instant lastContactAt;
    private volatile boolean running;
    private volatile @Nullable Thread thread;
    private Instant templateDue = Instant.EPOCH;

    public CloudLink(
            AgentProperties settings,
            ObjectProvider<CloudClient> clients,
            PrintQueue queue,
            PrintWorker worker,
            TemplateCache templates,
            ReceiptRenderer renderer,
            Clock clock) {
        this.settings = settings.cloud();
        this.clients = clients;
        this.queue = queue;
        this.worker = worker;
        this.templates = templates;
        this.renderer = renderer;
        this.clock = clock;
        this.state = this.settings.enabled() ? State.CONNECTING : State.DISABLED;
    }

    public State state() {
        return state;
    }

    public Optional<Instant> lastContactAt() {
        return Optional.ofNullable(lastContactAt);
    }

    @Override
    public void start() {
        if (!settings.enabled()) {
            log.atInfo().log("Cloud link disabled; printing for the till only");
            return;
        }
        CloudClient cloud = clients.getObject();
        running = true;
        thread = Thread.ofVirtual().name("cloud-link").start(() -> run(cloud));
    }

    @Override
    public void stop() {
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
            try {
                current.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run(CloudClient cloud) {
        int failures = 0;
        while (running) {
            try {
                once(cloud);
                failures = 0;
            } catch (CloudUnavailableException e) {
                failures++;
                offline(e.getMessage());
                pause(failures);
            } catch (RuntimeException e) {
                failures++;
                log.atError().setCause(e).log("Cloud link failed");
                pause(failures);
            }
        }
    }

    /** One round: results, the template when due, one long poll. */
    void once(CloudClient cloud) throws CloudUnavailableException {
        reportResults(cloud);
        if (!clock.instant().isBefore(templateDue)) {
            refreshTemplate(cloud);
        }
        Optional<JsonNode> job = cloud.nextJob();
        online();
        if (job.isPresent()) {
            accept(cloud, job.get());
            reportResults(cloud);
        }
    }

    private void accept(CloudClient cloud, JsonNode job) throws CloudUnavailableException {
        UUID jobId = UUID.fromString(job.path("id").asString());
        Optional<Request> known = queue.findByCloudJob(jobId);
        if (known.isPresent()) {
            // Handed out again: our result was lost on the way. Printed once; the result goes again.
            if (known.get().status() != PrintQueue.Status.QUEUED) {
                queue.reported(known.get().id(), false);
            }
            log.atInfo().addKeyValue("printJobId", jobId).log("Print job received again; not printed twice");
            return;
        }
        JsonNode version = job.path("templateVersion");
        if (version.canConvertToLong() && !templates.version().equals(Optional.of(version.asLong()))) {
            refreshTemplate(cloud);
        }
        byte[] document;
        try {
            document = renderer.escPos(ReceiptJson.receipt(job.path("receipt")), templates.current());
        } catch (IllegalArgumentException e) {
            log.atWarn().addKeyValue("printJobId", jobId).log("Print job refused: its receipt cannot be read");
            cloud.report(jobId, false, "The receipt cannot be read: " + e.getMessage(), null);
            return;
        }
        UUID id = queue.add(PrintQueue.Source.CLOUD, jobId, document);
        log.atInfo()
                .addKeyValue("printJobId", jobId)
                .addKeyValue("printRequestId", id)
                .log("Print job queued");
        worker.print(id);
    }

    private void reportResults(CloudClient cloud) throws CloudUnavailableException {
        for (Request done : queue.unreported()) {
            cloud.report(
                    Objects.requireNonNull(done.cloudJobId()),
                    done.status() == PrintQueue.Status.PRINTED,
                    done.lastError(),
                    done.resultPrinterStatus());
            queue.reported(done.id(), true);
            log.atInfo()
                    .addKeyValue("printJobId", done.cloudJobId())
                    .addKeyValue("status", done.status())
                    .log("Print job result reported");
        }
    }

    private void refreshTemplate(CloudClient cloud) throws CloudUnavailableException {
        JsonNode template = cloud.template();
        try {
            templates.store(template);
            log.atInfo()
                    .addKeyValue("templateVersion", template.path("version").asLong())
                    .log("Receipt template refreshed");
        } catch (IllegalArgumentException e) {
            log.atWarn().log("Receipt template refused; the cached one stays");
        }
        templateDue = clock.instant().plus(settings.templateRefresh());
    }

    private void online() {
        lastContactAt = clock.instant();
        if (state != State.ONLINE) {
            state = State.ONLINE;
            log.atInfo().log("Cloud reachable");
        }
    }

    private void offline(@Nullable String reason) {
        if (state != State.OFFLINE) {
            state = State.OFFLINE;
            log.atWarn().addKeyValue("reason", reason).log("Cloud unreachable; printing goes on locally");
        }
    }

    private void pause(int failures) {
        Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(failures - 1, 20));
        try {
            Thread.sleep(wait.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
