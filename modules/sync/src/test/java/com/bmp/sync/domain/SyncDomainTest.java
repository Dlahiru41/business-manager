package com.bmp.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.sync.domain.model.DeviceState;
import com.bmp.sync.domain.model.RecordOutcome;
import com.bmp.sync.domain.model.UploadRecordType;
import com.bmp.sync.domain.model.UploadStatus;
import com.bmp.sync.domain.service.ClockSkew;
import com.bmp.sync.domain.service.NumberGaps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @Test
    void syn06TheNumbersMissingBelowTheHighestAreGapsOnceEach() {
        assertThat(NumberGaps.newGaps(List.of(), Set.of())).isEmpty();
        assertThat(NumberGaps.newGaps(List.of(1L, 2L, 3L), Set.of()))
                .as("no gap")
                .isEmpty();
        assertThat(NumberGaps.newGaps(List.of(5L, 1L, 2L), Set.of())).containsExactly(3L, 4L);
        assertThat(NumberGaps.newGaps(List.of(5L, 1L, 2L), Set.of(3L)))
                .as("3 was reported before")
                .containsExactly(4L);
        assertThat(NumberGaps.newGaps(List.of(3L), Set.of()))
                .as("from the first number")
                .containsExactly(1L, 2L);
        assertThat(NumberGaps.newGaps(List.of(1L), Set.of())).isEmpty();
    }

    @Test
    void syn07AClockMoreThanFiveMinutesOffIsFlaggedEitherWay() {
        assertThat(ClockSkew.seconds(NOW.plusSeconds(90), NOW)).isEqualTo(90);
        assertThat(ClockSkew.seconds(NOW.minusSeconds(90), NOW)).isEqualTo(-90);
        assertThat(ClockSkew.flagged(300)).as("exactly five minutes").isFalse();
        assertThat(ClockSkew.flagged(-300)).isFalse();
        assertThat(ClockSkew.flagged(301)).isTrue();
        assertThat(ClockSkew.flagged(-301)).isTrue();
        assertThat(ClockSkew.flagged(0)).isFalse();
    }

    @Test
    void syn05AReplayOfAnAcceptedRecordIsADuplicateAndOfARejectedOneTheSameRejection() {
        UUID id = UUID.randomUUID();
        RecordOutcome accepted = RecordOutcome.accepted(UploadRecordType.BILL, id);
        RecordOutcome.Problem problem =
                new RecordOutcome.Problem("https://errors.bmp.example/sales/x", "X", "Refused", 422, null);
        RecordOutcome rejected = RecordOutcome.rejected(UploadRecordType.BILL, id, problem);

        assertThat(accepted.rejected()).isFalse();
        assertThat(accepted.replayed().status()).isEqualTo(UploadStatus.DUPLICATE);
        assertThat(accepted.replayed().problem()).isNull();
        assertThat(accepted.replayed().id()).isEqualTo(id);
        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.replayed()).isSameAs(rejected);
        assertThat(rejected.replayed().problem()).isEqualTo(problem);
        assertThatThrownBy(() -> new RecordOutcome(UploadRecordType.BILL, id, UploadStatus.REJECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecordOutcome(UploadRecordType.BILL, id, UploadStatus.ACCEPTED, problem))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDevicesStateKeepsItsFlagsAsGiven() {
        List<String> flags = new ArrayList<>(List.of("CLOCK_SKEW"));
        DeviceState state = new DeviceState(UUID.randomUUID(), UUID.randomUUID(), NOW, null, 400L, flags);
        flags.clear();

        assertThat(state.flags()).containsExactly("CLOCK_SKEW");
    }
}
