package com.bmp.tenant.application;

import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.spi.BusinessActivityProbe;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * Picks the {@link BusinessActivityProbe} with the highest precedence: a downstream implementation (sales, phase 03)
 * when present, otherwise the conservative default {@link TenantAgeActivityProbe}.
 */
@Component
public class BusinessActivityProbes {

    private final BusinessActivityProbe probe;

    public BusinessActivityProbes(List<BusinessActivityProbe> probes) {
        List<BusinessActivityProbe> ordered = new ArrayList<>(probes);
        AnnotationAwareOrderComparator.sort(ordered);
        this.probe = ordered.getFirst();
    }

    public boolean hasBusinessDocuments(TenantId tenant) {
        return probe.hasBusinessDocuments(tenant);
    }
}
