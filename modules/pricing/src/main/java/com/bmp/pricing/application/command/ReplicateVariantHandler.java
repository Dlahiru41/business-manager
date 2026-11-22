package com.bmp.pricing.application.command;

import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.pricing.domain.port.VariantRefStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the replica of catalog variants pricing validates and resolves against (T01-05). */
@Service
public class ReplicateVariantHandler {

    private final VariantRefStore variants;

    public ReplicateVariantHandler(VariantRefStore variants) {
        this.variants = variants;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replicate(VariantRef snapshot) {
        variants.upsert(snapshot);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deactivate(UUID variantId, UUID productId, long aggregateVersion) {
        variants.deactivate(variantId, productId, aggregateVersion);
    }
}
