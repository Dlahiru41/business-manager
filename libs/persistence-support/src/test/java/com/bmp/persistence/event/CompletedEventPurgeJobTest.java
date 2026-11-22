package com.bmp.persistence.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.CompletedEventPublications;

class CompletedEventPurgeJobTest {

    @Test
    void deletesCompletedPublicationsOlderThanTheRetention() {
        CompletedEventPublications completed = mock(CompletedEventPublications.class);

        new CompletedEventPurgeJob(completed, Duration.ofDays(7)).purge();

        verify(completed).deletePublicationsOlderThan(Duration.ofDays(7));
    }
}
