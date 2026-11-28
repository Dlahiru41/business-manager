package com.bmp.finance.adapter.in.job;

import com.bmp.finance.application.command.OverdueHandler;
import com.bmp.persistence.jdbc.PlatformAdminJdbc;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rule FIN-08, daily: the platform-admin connection finds the tenants with customer items past due; each is then
 * handled in its own tenant context, so one tenant's failure is logged and the others still run (ADR-032 §15).
 */
@Component
class ReceivableOverdueJob {

    private static final Logger log = LoggerFactory.getLogger(ReceivableOverdueJob.class);

    private final PlatformAdminJdbc platform;
    private final OverdueHandler overdue;

    ReceivableOverdueJob(PlatformAdminJdbc platform, OverdueHandler overdue) {
        this.platform = platform;
        this.overdue = overdue;
    }

    @Scheduled(cron = "${bmp.finance.overdue-cron:0 0 6 * * *}")
    @SchedulerLock(name = "finance-receivable-overdue", lockAtMostFor = "PT30M")
    public void run() {
        List<UUID> tenants = platform.jdbc()
                .sql("""
                        SELECT DISTINCT tenant_id FROM finance.open_item
                         WHERE party_type = 'CUSTOMER' AND status = 'OPEN' AND amount > 0 AND due_date < current_date
                        """)
                .query((rs, row) -> rs.getObject(1, UUID.class))
                .list();
        for (UUID tenant : tenants) {
            try {
                TenantContext.runWith(TenantId.of(tenant), overdue::report);
            } catch (RuntimeException e) {
                log.atError().setCause(e).addKeyValue("tenantId", tenant).log("Overdue receivables not reported");
            }
        }
    }
}
