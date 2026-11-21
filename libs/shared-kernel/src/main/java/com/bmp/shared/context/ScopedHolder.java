package com.bmp.shared.context;

import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A context value held in a {@link ScopedValue} (preferred: cannot leak, unbinds automatically) with a
 * {@link ThreadLocal} bridge for servlet filters, whose {@code chain.doFilter} cannot easily run inside a scope. A
 * scoped binding always wins over the bridge.
 */
final class ScopedHolder<T> {

    private final String name;
    private final ScopedValue<T> scoped = ScopedValue.newInstance();

    @SuppressWarnings("ThreadLocalUsage") // every ScopedHolder instance is itself held in a static final field
    private final ThreadLocal<@Nullable T> bridge = new ThreadLocal<>();

    ScopedHolder(String name) {
        this.name = name;
    }

    Optional<T> current() {
        if (scoped.isBound()) {
            return Optional.of(scoped.get());
        }
        return Optional.ofNullable(bridge.get());
    }

    void runWith(T value, Runnable action) {
        ScopedValue.where(scoped, Objects.requireNonNull(value, name)).run(action);
    }

    <R, X extends Throwable> R callWith(T value, ScopedValue.CallableOp<? extends R, X> action) throws X {
        return ScopedValue.where(scoped, Objects.requireNonNull(value, name)).call(action);
    }

    ContextBinding bind(T value) {
        if (scoped.isBound()) {
            throw new IllegalStateException(
                    name + " is already bound to a scope; bridge bindings are for filters only");
        }
        T previous = bridge.get();
        bridge.set(Objects.requireNonNull(value, name));
        return () -> {
            if (previous == null) {
                bridge.remove();
            } else {
                bridge.set(previous);
            }
        };
    }
}
