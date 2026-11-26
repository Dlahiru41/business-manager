package com.bmp.printagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.printagent.printer.PrinterStatus;
import com.bmp.printagent.queue.PrintQueue;
import com.bmp.printagent.queue.PrintQueue.Source;
import com.bmp.printagent.queue.PrintQueue.Status;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The queue on disk: requests survive a restart of the agent and are printed, reported and forgotten in order. */
class PrintQueueTest {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @TempDir
    Path dir;

    private PrintQueue open(Clock clock) {
        DataSource data = new DriverManagerDataSource("jdbc:h2:file:" + dir.resolve("print-agent"), "agent", "");
        Flyway.configure()
                .dataSource(data)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new PrintQueue(JdbcClient.create(data), clock);
    }

    @Test
    void aRequestKeptBeforeARestartIsStillThereWithItsDocument() {
        PrintQueue before = open(Clock.fixed(NOW, ZoneOffset.UTC));
        UUID first = before.add(Source.LOCAL, null, new byte[] {0x1b, 0x40, 0x00, 'A'});
        UUID second = before.add(Source.LOCAL, null, new byte[] {0x1b, 0x40, 'B'});

        PrintQueue after = open(Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        assertThat(after.queued()).isEqualTo(2);
        assertThat(after.head().orElseThrow().id()).as("oldest first").isEqualTo(first);
        assertThat(after.document(first)).containsExactly(0x1b, 0x40, 0x00, 'A');
        after.printed(first, 1, PrinterStatus.ONLINE);
        assertThat(after.head().orElseThrow().id()).isEqualTo(second);
    }

    @Test
    void aCloudJobIsKeptOnceAndItsResultWaitsUntilTheCloudTakesIt() {
        PrintQueue queue = open(Clock.fixed(NOW, ZoneOffset.UTC));
        UUID job = UUID.randomUUID();
        UUID id = queue.add(Source.CLOUD, job, new byte[] {1});

        assertThat(queue.add(Source.CLOUD, job, new byte[] {2}))
                .as("sent again")
                .isEqualTo(id);
        assertThat(queue.queued()).isEqualTo(1);
        queue.retryLater(id, 1, "x".repeat(600), NOW.plusSeconds(2));
        PrintQueue.Request waiting = queue.find(id).orElseThrow();
        assertThat(waiting.attempts()).isEqualTo(1);
        assertThat(waiting.lastError()).hasSize(500);
        assertThat(waiting.nextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(queue.unreported()).isEmpty();

        queue.failed(id, 5, "Paper out", PrinterStatus.PAPER_OUT);

        assertThat(queue.unreported()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(Status.FAILED);
            assertThat(r.cloudJobId()).isEqualTo(job);
            assertThat(r.resultPrinterStatus()).isEqualTo("PAPER_OUT");
        });
        assertThat(queue.purge(NOW.plus(Duration.ofDays(30))))
                .as("not before the cloud took the result")
                .isZero();
        queue.reported(id, true);
        assertThat(queue.unreported()).isEmpty();
        assertThat(queue.findByCloudJob(job).orElseThrow().reported()).isTrue();
        assertThat(queue.purge(NOW.minusSeconds(1))).as("too recent").isZero();
        assertThat(queue.purge(NOW.plusSeconds(1))).isEqualTo(1);
        assertThat(queue.find(id)).isEmpty();
    }
}
