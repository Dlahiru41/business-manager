package com.bmp.sales.application;

import com.bmp.sales.domain.port.BillRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.spi.ActivityEvidence;
import com.bmp.tenant.api.spi.BusinessActivityProbe;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rule TEN-02 as sales knows it: a tenant with any bill, a draft included, has business documents, so its currency
 * and time zone are locked for everyone. Without bills sales knows nothing of other documents and answers
 * {@link ActivityEvidence#NONE}; the tenant module keeps the strongest answer of all probes (ADR-027).
 */
@Component
@Order(0)
class SalesActivityProbe implements BusinessActivityProbe {

    private final BillRepository bills;
    private final TransactionTemplate readOnly;

    SalesActivityProbe(BillRepository bills, PlatformTransactionManager transactions) {
        this.bills = bills;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ActivityEvidence evidenceOf(TenantId tenant) {
        // Its own transaction in the tenant's context: row-level security sees exactly that tenant's bills.
        Boolean any = TenantContext.callWith(tenant, () -> readOnly.execute(status -> bills.anyBill()));
        return Boolean.TRUE.equals(any) ? ActivityEvidence.CONFIRMED : ActivityEvidence.NONE;
    }
}
