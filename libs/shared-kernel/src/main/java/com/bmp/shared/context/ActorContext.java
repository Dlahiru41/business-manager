package com.bmp.shared.context;

import java.util.Optional;

/** The actor (user and/or device) of the current unit of work, used for auditing and {@code created_by} columns. */
public final class ActorContext {

    private static final ScopedHolder<Actor> HOLDER = new ScopedHolder<>("ActorContext");

    private ActorContext() {}

    public static Optional<Actor> current() {
        return HOLDER.current();
    }

    /** The current actor, or {@link Actor#system()} when no actor is bound. */
    public static Actor currentOrSystem() {
        return current().orElse(Actor.system());
    }

    public static void runWith(Actor actor, Runnable action) {
        HOLDER.runWith(actor, action);
    }

    public static <R, X extends Throwable> R callWith(Actor actor, ScopedValue.CallableOp<? extends R, X> action)
            throws X {
        return HOLDER.callWith(actor, action);
    }

    /** Thread-local bridge for servlet filters; close it in a {@code finally}. */
    public static ContextBinding bind(Actor actor) {
        return HOLDER.bind(actor);
    }
}
