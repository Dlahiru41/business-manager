package com.bmp.pricing.application;

import com.bmp.catalog.api.spi.TaxCategoryLookup;
import com.bmp.pricing.domain.port.TaxCategoryRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Lets the catalog refuse products with a tax category that does not exist (catalog SPI, 01 par. 2). */
@Component
class TaxCategoryLookupImpl implements TaxCategoryLookup {

    private final TaxCategoryRepository categories;

    TaxCategoryLookupImpl(TaxCategoryRepository categories) {
        this.categories = categories;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID taxCategoryId) {
        return categories.findById(taxCategoryId).isPresent();
    }
}
