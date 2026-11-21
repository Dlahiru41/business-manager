package com.bmp.persistence.event;

import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.MdcKeys;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.event.EventMetadata;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Runs listener work in the context of the event being processed (07-events-and-messaging.md §4): the event's
 * tenant, the actor from its metadata, MDC keys ({@code tenantId}, {@code eventId}, {@code eventType},
 * {@code module}) and a span continuing the publisher's trace. Everything is restored when the work ends.
 * {@link EventContextInterceptor} applies it to every {@code @ApplicationModuleListener} automatically.
 */
public class EventContext {

    private static final Pattern MODULE_PACKAGE = Pattern.compile("^com\\.bmp\\.([a-z]+)\\..*");
    private static final String TRACEPARENT = "traceparent";

    private final @Nullable Tracer tracer;
    private final @Nullable Propagator propagator;

    public EventContext(@Nullable Tracer tracer, @Nullable Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** Work that may throw, so listener exceptions reach the caller unchanged. */
    @FunctionalInterface
    public interface Work<T> {
        @Nullable
        T run() throws Throwable;
    }

    /**
     * Runs {@code work} in the event's context.
     *
     * @param listener the listener class, used for the {@code module} MDC key
     */
    public <T> @Nullable T call(DomainEvent event, Class<?> listener, Work<T> work) throws Throwable {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.put(MdcKeys.TENANT_ID, event.tenantId().toString());
        MDC.put(MdcKeys.EVENT_ID, event.eventId().toString());
        MDC.put(MdcKeys.EVENT_TYPE, event.getClass().getSimpleName());
        String module = moduleOf(listener);
        if (module != null) {
            MDC.put(MdcKeys.MODULE, module);
        }
        EventMetadata metadata = event.metadata();
        Actor actor = new Actor(metadata.actorId(), metadata.deviceId());
        try {
            return TenantContext.callWith(
                    event.tenantId(), () -> ActorContext.callWith(actor, () -> inSpan(event, work)));
        } finally {
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            } else {
                MDC.clear();
            }
        }
    }

    private <T> @Nullable T inSpan(DomainEvent event, Work<T> work) throws Throwable {
        if (tracer == null) {
            return work.run();
        }
        Span span = startSpan(event, tracer);
        try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
            return work.run();
        } catch (Throwable failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private Span startSpan(DomainEvent event, Tracer activeTracer) {
        String name = "event " + event.getClass().getSimpleName();
        String traceparent = event.metadata().traceparent();
        if (traceparent != null && propagator != null) {
            Map<String, String> carrier = new HashMap<>();
            carrier.put(TRACEPARENT, traceparent);
            return propagator
                    .extract(carrier, (map, key) -> map.get(key))
                    .name(name)
                    .tag("bmp.tenant_id", event.tenantId().toString())
                    .tag("bmp.event_id", event.eventId().toString())
                    .start();
        }
        return activeTracer
                .nextSpan()
                .name(name)
                .tag("bmp.tenant_id", event.tenantId().toString())
                .tag("bmp.event_id", event.eventId().toString())
                .start();
    }

    static @Nullable String moduleOf(Class<?> type) {
        Matcher matcher = MODULE_PACKAGE.matcher(type.getName());
        return matcher.matches() ? matcher.group(1) : null;
    }
}
