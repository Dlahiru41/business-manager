package com.bmp.shared.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void shouldGenerateTimeOrderedUuidV7() {
        IdGenerator ids = IdGenerator.uuidV7();
        List<UUID> generated = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            generated.add(ids.newUuid());
        }

        assertThat(generated).allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
        assertThat(generated).doesNotHaveDuplicates();
        assertThat(generated.getFirst().getMostSignificantBits() >>> 16)
                .isLessThanOrEqualTo(generated.getLast().getMostSignificantBits() >>> 16);
    }

    @Test
    void shouldCreateTypedIdsThroughFactory() {
        UUID fixed = UUID.fromString("0193a6f1-0000-7000-8000-000000000001");
        IdGenerator deterministic = () -> fixed;

        assertThat(deterministic.next(TenantId::new)).isEqualTo(TenantId.of(fixed));
    }

    @Test
    void shouldParseAndRenderTypedIds() {
        String raw = "0193a6f1-0000-7000-8000-000000000002";

        assertThat(TenantId.of(raw)).hasToString(raw);
        assertThat(UserId.of(raw).value()).isEqualTo(UUID.fromString(raw));
        assertThat(DeviceId.of(raw)).hasToString(raw);
        assertThat(LocationId.of(raw)).hasToString(raw);
        assertThat(RegisterId.of(raw)).hasToString(raw);
        assertThat(TenantId.of(raw)).isEqualTo(TenantId.of(UUID.fromString(raw)));
    }

    @Test
    void shouldRejectMalformedOrNullIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> TenantId.of("not-a-uuid"));
        assertThatNullPointerException().isThrownBy(() -> new TenantId(null));
        assertThatNullPointerException().isThrownBy(() -> new UserId(null));
        assertThatNullPointerException().isThrownBy(() -> new DeviceId(null));
        assertThatNullPointerException().isThrownBy(() -> new LocationId(null));
        assertThatNullPointerException().isThrownBy(() -> new RegisterId(null));
    }
}
