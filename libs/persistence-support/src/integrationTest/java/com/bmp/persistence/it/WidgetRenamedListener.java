package com.bmp.persistence.it;

import com.bmp.persistence.event.InboxGuard;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.MdcKeys;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;

public class WidgetRenamedListener {

    public record Seen(
            UUID eventId,
            TenantId contextTenant,
            String databaseTenant,
            String mdcTenant,
            String mdcEventId,
            UserId actor,
            boolean firstDelivery) {}

    private final List<Seen> observed = new CopyOnWriteArrayList<>();
    private final JdbcClient jdbc;
    private final InboxGuard inbox;

    public WidgetRenamedListener(JdbcClient jdbc, InboxGuard inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    void on(WidgetRenamed event) {
        if (event.reject()) {
            throw new BusinessRuleViolation(PlatformErrorCode.VALIDATION_FAILED, Map.of("reason", "test"));
        }
        boolean first = inbox.firstDelivery("it.WidgetRenamedListener", event.eventId());
        String databaseTenant = jdbc.sql("SELECT current_setting('app.tenant_id', true)")
                .query(String.class)
                .single();
        observed.add(new Seen(
                event.eventId(),
                TenantContext.require(),
                databaseTenant,
                MDC.get(MdcKeys.TENANT_ID),
                MDC.get(MdcKeys.EVENT_ID),
                ActorContext.currentOrSystem().userId(),
                first));
    }

    public List<Seen> observed() {
        return observed;
    }
}
