package com.bmp.pricing.application.command;

import com.bmp.pricing.application.PriceChangeGuard;
import com.bmp.pricing.application.PricingLoader;
import com.bmp.pricing.application.PricingSettings;
import com.bmp.pricing.application.query.PriceListView;
import com.bmp.pricing.domain.model.ListChannel;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.PricingStatus;
import com.bmp.pricing.domain.port.PriceListRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and changes price lists (PRC-01, PRC-08). */
@Service
public class PriceListHandlers {

    /** Priority of lists created without one; the seeded Retail and Wholesale lists have it too. */
    public static final int DEFAULT_PRIORITY = 100;

    private final PriceListRepository lists;
    private final PricingLoader loader;
    private final PricingSettings settings;
    private final PriceChangeGuard guard;
    private final IdGenerator ids;

    public PriceListHandlers(
            PriceListRepository lists,
            PricingLoader loader,
            PricingSettings settings,
            PriceChangeGuard guard,
            IdGenerator ids) {
        this.lists = lists;
        this.loader = loader;
        this.settings = settings;
        this.guard = guard;
        this.ids = ids;
    }

    /**
     * A new list in the tenant currency.
     *
     * @param taxInclusive defaults to the tenant setting {@code pricing.pricesTaxInclusive}
     */
    public record CreatePriceList(
            String code,
            String name,
            ListChannel channel,
            @Nullable UUID customerGroupId,
            @Nullable UUID customerId,
            @Nullable Boolean taxInclusive,
            @Nullable Integer priority) {}

    /** PATCH semantics: {@code null} fields stay as they are. */
    public record UpdatePriceList(
            UUID id,
            long expectedVersion,
            @Nullable String name,
            @Nullable Integer priority,
            @Nullable PricingStatus status) {}

    @Transactional
    @Observed(name = "bmp.pricing.create-price-list")
    public PriceListView create(CreatePriceList command) {
        guard.requireAllowed();
        Boolean inclusive = command.taxInclusive();
        Integer priority = command.priority();
        PriceList list = PriceList.create(
                ids.newUuid(),
                TenantContext.require(),
                command.code(),
                command.name(),
                command.channel(),
                command.customerGroupId(),
                command.customerId(),
                settings.tenant().currency(),
                inclusive != null ? inclusive : settings.pricesTaxInclusive(),
                priority != null ? priority : DEFAULT_PRIORITY);
        return PriceListView.of(lists.saveAndFlush(list));
    }

    @Transactional
    @Observed(name = "bmp.pricing.update-price-list")
    public PriceListView update(UpdatePriceList command) {
        guard.requireAllowed();
        PriceList list = loader.list(command.id());
        IfMatch.check(command.expectedVersion(), list.version());
        if (command.name() != null) {
            list.rename(command.name());
        }
        if (command.priority() != null) {
            list.reprioritise(command.priority());
        }
        if (command.status() == PricingStatus.INACTIVE) {
            list.deactivate();
        } else if (command.status() == PricingStatus.ACTIVE) {
            list.activate();
        }
        return PriceListView.of(lists.saveAndFlush(list));
    }
}
