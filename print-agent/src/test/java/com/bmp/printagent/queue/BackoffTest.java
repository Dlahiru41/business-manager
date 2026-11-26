package com.bmp.printagent.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void aRequestWaitsOneSecondThenTwiceAsLongEachTimeAtMostTheLimit() {
        Duration max = Duration.ofSeconds(60);

        assertThat(PrintWorker.backoff(1, max)).hasSeconds(1);
        assertThat(PrintWorker.backoff(2, max)).hasSeconds(2);
        assertThat(PrintWorker.backoff(6, max)).hasSeconds(32);
        assertThat(PrintWorker.backoff(7, max)).hasSeconds(60);
        assertThat(PrintWorker.backoff(1_000, max)).hasSeconds(60);
    }
}
