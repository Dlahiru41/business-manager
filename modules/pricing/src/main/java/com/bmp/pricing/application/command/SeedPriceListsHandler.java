package com.bmp.pricing.application.command;

import com.bmp.pricing.domain.model.ListChannel;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.port.PriceListRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Rule PRC-01: every new tenant gets a Retail and a Wholesale price list, tax-inclusive by default. */
@Service
public class SeedPriceListsHandler {

    private static final Logger log = LoggerFactory.getLogger(SeedPriceListsHandler.class);

    private final PriceListRepository lists;
    private final IdGenerator ids;

    public SeedPriceListsHandler(PriceListRepository lists, IdGenerator ids) {
        this.lists = lists;
        this.ids = ids;
    }

    /** Creates the lists that do not exist yet; returns how many were created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int seed(String currency) {
        int created = 0;
        for (ListChannel channel : List.of(ListChannel.RETAIL, ListChannel.WHOLESALE)) {
            if (lists.findByCode(channel.name()).isEmpty()) {
                String name = channel == ListChannel.RETAIL ? "Retail" : "Wholesale";
                lists.saveAndFlush(PriceList.create(
                        ids.newUuid(),
                        TenantContext.require(),
                        channel.name(),
                        name,
                        channel,
                        null,
                        null,
                        currency,
                        true,
                        PriceListHandlers.DEFAULT_PRIORITY));
                created++;
            }
        }
        log.info("Price lists seeded created={}", created);
        return created;
    }
}
