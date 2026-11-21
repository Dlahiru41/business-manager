package com.bmp.persistence.event;

import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.MdcKeys;
import com.bmp.shared.event.EventMetadata;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Builds {@link EventMetadata} for events being published: the current actor, the request id and the W3C
 * {@code traceparent} of the current span, so listeners can continue the same trace (05-logging §7).
 */
public class EventMetadataSource {

    private final @Nullable Tracer tracer;

    public EventMetadataSource(@Nullable Tracer tracer) {
        this.tracer = tracer;
    }

    public EventMetadata current() {
        Actor actor = ActorContext.currentOrSystem();
        return new EventMetadata(actor.userId(), actor.deviceId(), MDC.get(MdcKeys.REQUEST_ID), traceparent());
    }

    private @Nullable String traceparent() {
        Span span = tracer != null ? tracer.currentSpan() : null;
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        boolean sampled = Boolean.TRUE.equals(context.sampled());
        return "00-" + context.traceId() + "-" + context.spanId() + (sampled ? "-01" : "-00");
    }
}
