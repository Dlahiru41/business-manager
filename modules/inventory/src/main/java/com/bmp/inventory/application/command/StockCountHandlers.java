package com.bmp.inventory.application.command;

import com.bmp.inventory.application.InventoryEvents;
import com.bmp.inventory.application.InventoryLoader;
import com.bmp.inventory.application.InventorySettings;
import com.bmp.inventory.application.StockLedger;
import com.bmp.inventory.application.StockReferences;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.MovementType;
import com.bmp.inventory.domain.model.StockCount;
import com.bmp.inventory.domain.model.StockCountLine;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.port.StockCountRepository;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.inventory.domain.service.StockLine;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.IdGenerator;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock counts (INV-12): counting, submitting, posting the variances as COUNT_ADJ movements, cancelling. */
@Service
public class StockCountHandlers {

    private static final Logger log = LoggerFactory.getLogger(StockCountHandlers.class);

    private final StockCountRepository counts;
    private final InventoryLoader loader;
    private final StockReferences references;
    private final StockLedger ledger;
    private final InventorySettings settings;
    private final InventoryEvents events;
    private final IdGenerator ids;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters")
    public StockCountHandlers(
            StockCountRepository counts,
            InventoryLoader loader,
            StockReferences references,
            StockLedger ledger,
            InventorySettings settings,
            InventoryEvents events,
            IdGenerator ids,
            Clock clock) {
        this.counts = counts;
        this.loader = loader;
        this.references = references;
        this.ledger = ledger;
        this.settings = settings;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /** A counted quantity in base units; the state defaults to the base unit's. */
    public record Counted(
            UUID variantId,
            @Nullable String stockState,
            @Nullable UUID batchId,
            BigDecimal countedQty,
            @Nullable String varianceReason) {}

    @Transactional
    @Observed(name = "bmp.inventory.open-count")
    public StockCount open(UUID locationId, @Nullable String note, List<UUID> variantIds) {
        references.location(locationId);
        variantIds.forEach(references::item);
        return counts.saveAndFlush(
                StockCount.open(ids.newUuid(), TenantContext.require(), locationId, variantIds, note, clock.instant()));
    }

    @Transactional
    @Observed(name = "bmp.inventory.record-count")
    public StockCount record(UUID countId, long expectedVersion, List<Counted> lines) {
        StockCount count = loader.count(countId);
        IfMatch.check(expectedVersion, count.version());
        Instant now = clock.instant();
        for (Counted line : lines) {
            StockItem item = references.item(line.variantId());
            String state = item.resolve(null, line.stockState(), BigDecimal.ONE).stockState();
            UUID batch =
                    line.batchId() == null ? BalanceKey.NO_BATCH : references.batch(item.variantId(), line.batchId());
            BalanceKey key = new BalanceKey(item.variantId(), count.locationId(), state, batch);
            count.record(
                    key,
                    line.countedQty(),
                    line.varianceReason(),
                    () -> ledger.states(List.of(key))
                            .getOrDefault(key, BalanceState.empty())
                            .onHand(),
                    ids::newUuid,
                    now);
        }
        return counts.saveAndFlush(count);
    }

    @Transactional
    @Observed(name = "bmp.inventory.submit-count")
    public StockCount submit(UUID countId) {
        StockCount count = loader.count(countId);
        count.submit(clock.instant());
        StockCount saved = counts.saveAndFlush(count);
        saved.lines();
        return saved;
    }

    /** Rule INV-12: books each line's variance (counted − system at its snapshot) at current cost. */
    @Transactional
    @Observed(name = "bmp.inventory.post-count")
    public StockCount post(UUID countId) {
        StockCount count = loader.count(countId);
        Instant now = clock.instant();
        List<StockLine> stock = new ArrayList<>();
        Map<UUID, UUID> lineOf = new HashMap<>();
        for (StockCountLine line : count.lines()) {
            if (line.variance().signum() != 0) {
                stock.add(new StockLine(
                        line.key(count.locationId()),
                        MovementType.COUNT_ADJ,
                        line.variance(),
                        null,
                        true,
                        "STOCK_COUNT",
                        count.id(),
                        line.id(),
                        line.varianceReason(),
                        now));
            }
        }
        List<PlannedMovement> posted = ledger.post(stock);
        Map<UUID, BigDecimal> costs = new HashMap<>();
        posted.stream().filter(m -> m.sourceLineId() != null).forEach(m -> {
            UUID line = java.util.Objects.requireNonNull(m.sourceLineId());
            costs.merge(line, m.costAmount(), BigDecimal::add);
            lineOf.put(m.id(), line);
        });
        var user = ActorContext.currentOrSystem().userId();
        count.post(costs, now, user == null ? null : user.value());
        StockCount saved = counts.saveAndFlush(count);
        events.countPosted(saved, settings.tenant().currency(), posted);
        log.atInfo()
                .addKeyValue("countId", saved.id())
                .addKeyValue("adjustments", lineOf.size())
                .log("Stock count posted");
        return saved;
    }

    @Transactional
    @Observed(name = "bmp.inventory.cancel-count")
    public StockCount cancel(UUID countId) {
        StockCount count = loader.count(countId);
        count.cancel();
        StockCount saved = counts.saveAndFlush(count);
        saved.lines();
        return saved;
    }
}
