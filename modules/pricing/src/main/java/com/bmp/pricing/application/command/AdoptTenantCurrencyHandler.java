package com.bmp.pricing.application.command;

import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.port.PriceListRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps price lists in the tenant currency (TEN-02 lets it change before business starts): lists that never held a
 * price follow the new currency; lists with prices keep theirs and stop pricing baskets (ADR-021).
 */
@Service
public class AdoptTenantCurrencyHandler {

    private static final Logger log = LoggerFactory.getLogger(AdoptTenantCurrencyHandler.class);

    private final PriceListRepository lists;

    public AdoptTenantCurrencyHandler(PriceListRepository lists) {
        this.lists = lists;
    }

    /** Returns how many lists changed currency. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int adopt(String currency) {
        int adopted = 0;
        int kept = 0;
        for (PriceList list : lists.findAll()) {
            if (list.adoptCurrency(currency)) {
                lists.saveAndFlush(list);
                adopted++;
            } else if (!list.currency().equals(currency)) {
                kept++;
            }
        }
        if (kept > 0) {
            log.atWarn()
                    .addKeyValue("currency", currency)
                    .addKeyValue("listsKept", kept)
                    .log("Price lists with prices keep their former currency and no longer price baskets");
        }
        log.atInfo()
                .addKeyValue("currency", currency)
                .addKeyValue("listsAdopted", adopted)
                .log("Price lists follow the tenant currency");
        return adopted;
    }
}
