package com.bmp.documents.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditStampsTest {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @Test
    void stampsSayWhenAndByWhomAndAreTheEpochBeforeTheFirstSave() {
        AuditStamps stamps = new AuditStamps();
        assertThat(stamps.createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(stamps.updatedAt()).isEqualTo(Instant.EPOCH);

        stamps.created(NOW, null);
        assertThat(stamps.createdAt()).isEqualTo(NOW);
        assertThat(stamps.updatedAt())
                .as("a print agent's own requests have no user")
                .isEqualTo(NOW);

        stamps.modified(NOW.plusSeconds(60), UserId.of(UUID.randomUUID()));
        assertThat(stamps.updatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(stamps.createdAt()).isEqualTo(NOW);
    }
}
