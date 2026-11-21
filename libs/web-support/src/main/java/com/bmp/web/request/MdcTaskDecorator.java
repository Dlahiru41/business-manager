package com.bmp.web.request;

import com.bmp.shared.context.Actor;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import java.util.Map;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the MDC, tenant and actor of the submitting thread into async tasks so logs stay correlated and data access
 * stays tenant-scoped (05-logging-and-observability.md §2). Everything is cleared when the task ends.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Optional<TenantId> tenant = TenantContext.current();
        Optional<Actor> actor = ActorContext.current();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                Runnable withActor = actor.<Runnable>map(a -> () -> ActorContext.runWith(a, task))
                        .orElse(task);
                tenant.ifPresentOrElse(t -> TenantContext.runWith(t, withActor), withActor);
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
