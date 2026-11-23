package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.model.ValuationGroup;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Reads {@code groupBy=product} as the contract spells it (lower case); unknown values are a 400. */
@Component
class ValuationGroupConverter implements Converter<String, ValuationGroup> {

    @Override
    public ValuationGroup convert(String source) {
        return ValuationGroup.fromValue(source);
    }
}
