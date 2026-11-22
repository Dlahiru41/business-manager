package com.bmp.shared.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    private static final TenantId TENANT_A = TenantId.of(UUID.randomUUID());
    private static final TenantId TENANT_B = TenantId.of(UUID.randomUUID());

    @Test
    void shouldBeEmptyByDefaultAndRequireThrowsTenantContextMissing() {
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require)
                .isInstanceOfSatisfying(
                        TechnicalException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.TENANT_CONTEXT_MISSING));
    }

    @Test
    void shouldBindForTheDurationOfRunWithOnly() {
        TenantContext.runWith(
                TENANT_A, () -> assertThat(TenantContext.require()).isEqualTo(TENANT_A));

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void shouldReturnValueFromCallWithAndSupportNesting() {
        TenantId seen =
                TenantContext.callWith(TENANT_A, () -> TenantContext.callWith(TENANT_B, TenantContext::require));

        assertThat(seen).isEqualTo(TENANT_B);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void shouldBridgeThroughThreadLocalAndRestoreOnClose() {
        try (ContextBinding _ = TenantContext.bind(TENANT_A)) {
            assertThat(TenantContext.require()).isEqualTo(TENANT_A);
            try (ContextBinding _ = TenantContext.bind(TENANT_B)) {
                assertThat(TenantContext.require()).isEqualTo(TENANT_B);
            }
            assertThat(TenantContext.require()).isEqualTo(TENANT_A);
        }
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void shouldPreferScopedBindingOverBridge() {
        try (ContextBinding _ = TenantContext.bind(TENANT_A)) {
            TenantContext.runWith(
                    TENANT_B, () -> assertThat(TenantContext.require()).isEqualTo(TENANT_B));
            assertThat(TenantContext.require()).isEqualTo(TENANT_A);
        }
    }

    @Test
    void shouldRefuseBridgeBindingInsideAScope() {
        TenantContext.runWith(
                TENANT_A, () -> assertThatIllegalStateException().isThrownBy(() -> TenantContext.bind(TENANT_B)));
    }

    @Test
    void shouldNotLeakToOtherThreads() throws InterruptedException {
        AtomicReference<Optional<TenantId>> seenByOtherThread = new AtomicReference<>();
        try (ContextBinding _ = TenantContext.bind(TENANT_A)) {
            Thread other = Thread.ofVirtual().start(() -> seenByOtherThread.set(TenantContext.current()));
            other.join();
        }

        assertThat(seenByOtherThread.get()).isEmpty();
    }

    @Test
    void actorContextShouldDefaultToSystemAndBindLikeTenantContext() {
        Actor cashier = new Actor(UserId.of(UUID.randomUUID()), DeviceId.of(UUID.randomUUID()));

        assertThat(ActorContext.currentOrSystem().isSystem()).isTrue();
        ActorContext.runWith(
                cashier, () -> assertThat(ActorContext.currentOrSystem()).isEqualTo(cashier));
        assertThat(ActorContext.callWith(cashier, ActorContext::currentOrSystem))
                .isEqualTo(cashier);
        try (ContextBinding _ = ActorContext.bind(cashier)) {
            assertThat(ActorContext.current()).contains(cashier);
        }
        assertThat(ActorContext.current()).isEmpty();
        assertThat(cashier.isSystem()).isFalse();
        assertThat(Actor.user(UserId.of(UUID.randomUUID())).isSystem()).isFalse();
        assertThat(new Actor(null, DeviceId.of(UUID.randomUUID())).isSystem()).isFalse();
    }
}
