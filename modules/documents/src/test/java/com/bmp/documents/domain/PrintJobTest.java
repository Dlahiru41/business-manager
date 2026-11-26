package com.bmp.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.documents.domain.model.PrintAgent;
import com.bmp.documents.domain.model.PrintJob;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.id.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrintJobTest {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    private static PrintJob job() {
        return PrintJob.queue(UUID.randomUUID(), TENANT, UUID.randomUUID(), "RECEIPT", null, "{}", 3L);
    }

    private static String code(Throwable e) {
        return ((BmpException) e).errorCode().code();
    }

    @Test
    void doc05AJobIsHandedOutOnceUntilItsResultIsLateThenAgain() {
        PrintJob job = job();
        assertThat(job.due(NOW)).isTrue();
        assertThat(job.status()).isEqualTo("QUEUED");

        job.send(NOW);

        assertThat(job.status()).isEqualTo("SENT");
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.due(NOW.plusSeconds(59)))
                .as("waiting for the agent's result")
                .isFalse();
        assertThatThrownBy(() -> job.send(NOW.plusSeconds(30)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("PRINT_JOB_STATE_INVALID"));
        assertThat(job.due(NOW.plus(PrintJob.RESULT_TIMEOUT)))
                .as("no answer within a minute")
                .isTrue();
        job.send(NOW.plus(PrintJob.RESULT_TIMEOUT));
        assertThat(job.attempts()).isEqualTo(2);
    }

    @Test
    void doc05TheResultIsFinalAndARepeatChangesNothing() {
        PrintJob printed = job();
        assertThatThrownBy(() -> printed.report(true, null, NOW))
                .as("not handed out yet")
                .satisfies(e -> assertThat(code(e)).isEqualTo("PRINT_JOB_STATE_INVALID"));
        printed.send(NOW);
        printed.report(true, null, NOW.plusSeconds(5));
        printed.report(true, null, NOW.plusSeconds(6));
        assertThat(printed.status()).isEqualTo("PRINTED");
        assertThat(printed.completedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(printed.due(NOW.plus(Duration.ofHours(1)))).isFalse();
        assertThatThrownBy(() -> printed.report(false, "Paper out", NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo("PRINT_JOB_STATE_INVALID"));

        PrintJob failed = job();
        failed.send(NOW);
        failed.report(false, "Paper out", NOW);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).isEqualTo("Paper out");
        assertThat(failed.templateVersion()).isEqualTo(3L);
        assertThat(failed.documentType()).isEqualTo("RECEIPT");
    }

    @Test
    void anAgentPrintsOnFiftyEightOrEightyMillimetrePaperAndReportsItsPrinter() {
        PrintAgent agent = PrintAgent.register(UUID.randomUUID(), TENANT, UUID.randomUUID(), "Counter", "TM-T20", 80);
        assertThat(agent.status()).isEqualTo("OFFLINE");
        agent.seen("PAPER_OUT", NOW);
        assertThat(agent.status()).isEqualTo("PAPER_OUT");
        assertThat(agent.lastSeenAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> PrintAgent.register(UUID.randomUUID(), TENANT, UUID.randomUUID(), "Odd", null, 76))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
