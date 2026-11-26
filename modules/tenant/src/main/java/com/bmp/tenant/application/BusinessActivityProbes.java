package com.bmp.tenant.application;

import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.spi.ActivityEvidence;
import com.bmp.tenant.api.spi.BusinessActivityProbe;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Asks every {@link BusinessActivityProbe} and keeps the strongest answer (TEN-02, ADR-027): each downstream module
 * knows only its own documents (sales its bills), so one module's {@link ActivityEvidence#NONE} never overrules another
 * module's documents or the conservative default {@link TenantAgeActivityProbe}.
 */
@Component
public class BusinessActivityProbes {

    private final List<BusinessActivityProbe> probes;

    public BusinessActivityProbes(List<BusinessActivityProbe> probes) {
        if (probes.isEmpty()) {
            throw new IllegalArgumentException("TEN-02 needs at least the default activity probe");
        }
        this.probes = List.copyOf(probes);
    }

    public ActivityEvidence evidenceOf(TenantId tenant) {
        ActivityEvidence strongest = ActivityEvidence.NONE;
        for (BusinessActivityProbe probe : probes) {
            ActivityEvidence evidence = probe.evidenceOf(tenant);
            if (evidence == ActivityEvidence.CONFIRMED) {
                return evidence;
            }
            if (evidence.compareTo(strongest) > 0) {
                strongest = evidence;
            }
        }
        return strongest;
    }
}
