package com.bmp.persistence.event;

import com.bmp.shared.error.DomainException;
import com.bmp.shared.event.DomainEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;

/**
 * Wraps every {@code @ApplicationModuleListener} invocation, outside its transaction so the transaction starts with
 * the event's tenant bound: restores context through {@link EventContext} and parks non-retryable failures
 * ({@link DomainException}) in {@link FailedEventStore} with metric {@code bmp.events.failed} and one ERROR log.
 * Any other failure propagates, leaving the publication incomplete for the resubmission job.
 */
public class EventContextInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EventContextInterceptor.class);

    private final EventContext context;
    private final FailedEventStore failedEvents;
    private final MeterRegistry meters;

    public EventContextInterceptor(EventContext context, FailedEventStore failedEvents, MeterRegistry meters) {
        this.context = context;
        this.failedEvents = failedEvents;
        this.meters = meters;
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        DomainEvent event = eventArgument(invocation.getArguments());
        if (event == null) {
            return invocation.proceed();
        }
        Class<?> listener = invocation.getMethod().getDeclaringClass();
        return context.call(event, listener, () -> {
            try {
                return invocation.proceed();
            } catch (DomainException rejected) {
                park(listenerId(invocation.getMethod()), listener, event, rejected);
                return null;
            }
        });
    }

    private void park(String consumer, Class<?> listener, DomainEvent event, DomainException rejected) {
        failedEvents.park(consumer, event, rejected);
        Counter.builder("bmp.events.failed")
                .description("Events a listener rejected or gave up on")
                .tag("listener", listener.getSimpleName())
                .register(meters)
                .increment();
        log.atError()
                .setCause(rejected)
                .addKeyValue("listener", listener.getSimpleName())
                .addKeyValue("code", rejected.errorCode().code())
                .log(
                        "Event rejected by listener and parked eventId={} code={}",
                        event.eventId(),
                        rejected.errorCode().code());
    }

    /** The listener id exactly as Spring Modulith derives it for the event publication registry. */
    static String listenerId(Method method) {
        return new TransactionalApplicationListenerMethodAdapter("bmp", method.getDeclaringClass(), method)
                .getListenerId();
    }

    private static @Nullable DomainEvent eventArgument(@Nullable Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof DomainEvent event) {
                return event;
            }
        }
        return null;
    }
}
