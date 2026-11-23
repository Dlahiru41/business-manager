package com.bmp.inventory.application;

import com.bmp.inventory.api.events.StockLevelLow;
import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.BalanceState;
import com.bmp.inventory.domain.model.BatchInfo;
import com.bmp.inventory.domain.model.CostLayer;
import com.bmp.inventory.domain.model.ReorderPolicy;
import com.bmp.inventory.domain.model.StockFlag;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.policy.CostingPolicy;
import com.bmp.inventory.domain.policy.FifoCostingMarker;
import com.bmp.inventory.domain.port.BalanceStore;
import com.bmp.inventory.domain.port.BatchStore;
import com.bmp.inventory.domain.port.CostLayerStore;
import com.bmp.inventory.domain.port.MovementLedger;
import com.bmp.inventory.domain.port.ReorderPolicyStore;
import com.bmp.inventory.domain.port.StockItemStore;
import com.bmp.inventory.domain.service.PlannedMovement;
import com.bmp.inventory.domain.service.ReorderRule;
import com.bmp.inventory.domain.service.StockLine;
import com.bmp.inventory.domain.service.StockPlanner;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.VariantId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts stock movements (T02-04, INV-01..INV-08, INV-13, INV-14): locks every balance the lines touch in key order,
 * spreads outgoing lines of batch-tracked variants over their batches earliest expiry first, costs the lines, appends
 * them to the ledger and updates the balances in the same transaction, then raises or clears reorder alerts.
 */
@Component
public class StockLedger {

    private static final Logger log = LoggerFactory.getLogger(StockLedger.class);

    private final BalanceStore balances;
    private final CostLayerStore layers;
    private final MovementLedger ledger;
    private final StockItemStore items;
    private final BatchStore batches;
    private final ReorderPolicyStore reorder;
    private final InventorySettings settings;
    private final ApplicationEventPublisher publisher;
    private final EventStampSource stamps;
    private final IdGenerator ids;
    private final Clock clock;
    private final MeterRegistry meters;

    @SuppressWarnings("TooManyParameters") // the ports and platform services one posting needs
    public StockLedger(
            BalanceStore balances,
            CostLayerStore layers,
            MovementLedger ledger,
            StockItemStore items,
            BatchStore batches,
            ReorderPolicyStore reorder,
            InventorySettings settings,
            ApplicationEventPublisher publisher,
            EventStampSource stamps,
            IdGenerator ids,
            Clock clock,
            ObjectProvider<MeterRegistry> meters) {
        this.balances = balances;
        this.layers = layers;
        this.ledger = ledger;
        this.items = items;
        this.batches = batches;
        this.reorder = reorder;
        this.settings = settings;
        this.publisher = publisher;
        this.stamps = stamps;
        this.ids = ids;
        this.clock = clock;
        this.meters = meters.getIfUnique(SimpleMeterRegistry::new);
    }

    /** Locks balances ahead of several postings in one transaction (a transformation's inputs and outputs). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Collection<BalanceKey> keys) {
        balances.lock(withBatches(keys));
    }

    /** The current state of locked balances, for callers that need costs before posting. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<BalanceKey, BalanceState> states(Collection<BalanceKey> keys) {
        return states(keys, settings.costing());
    }

    /**
     * Posts the lines in order and returns the movements with their costs, corrections included.
     *
     * @param lines on keys without a batch, outgoing lines of batch-tracked variants are spread over the batches
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PlannedMovement> post(List<StockLine> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        CostingPolicy costing = settings.costing();
        Set<BalanceKey> keys = withBatches(lines.stream().map(StockLine::key).toList());
        Map<BalanceKey, BalanceState> locked = states(keys, costing);
        List<StockLine> spread = spread(lines, locked);
        Set<BalanceKey> touched = new TreeSet<>();
        spread.forEach(l -> touched.add(l.key()));
        Map<BalanceKey, BalanceState> before = new HashMap<>(locked);
        StockPlanner.Plan plan = StockPlanner.plan(locked, spread, costing, ids::newUuid);
        ledger.append(plan.movements(), settings.tenant().currency(), clock.instant(), userId(), deviceId());
        balances.update(plan.balances(), plan.movements());
        if (costing instanceof FifoCostingMarker) {
            for (BalanceKey key : touched) {
                layers.save(
                        key,
                        before.getOrDefault(key, BalanceState.empty()).layers(),
                        plan.balances().getOrDefault(key, BalanceState.empty()).layers());
            }
        }
        plan.movements().stream()
                .filter(m -> m.flags().contains(StockFlag.NEGATIVE_STOCK))
                .forEach(m -> {
                    meters.counter(
                                    "bmp.inventory.negative_stock",
                                    "type",
                                    m.type().name())
                            .increment();
                    log.atWarn()
                            .addKeyValue("variantId", m.key().variantId())
                            .addKeyValue("locationId", m.key().locationId())
                            .addKeyValue("stockState", m.key().stockState())
                            .addKeyValue("movementType", m.type())
                            .log("Stock went negative");
                });
        evaluateReorder(plan.movements());
        return plan.movements();
    }

    private Map<BalanceKey, BalanceState> states(Collection<BalanceKey> keys, CostingPolicy costing) {
        Map<BalanceKey, BalanceState> locked = new LinkedHashMap<>(balances.lock(keys));
        if (costing instanceof FifoCostingMarker) {
            Map<BalanceKey, List<CostLayer>> loaded = layers.load(locked.keySet());
            locked.replaceAll((key, state) -> new BalanceState(
                    state.onHand(), state.value(), state.avgUnitCost(), loaded.getOrDefault(key, List.of())));
        }
        return locked;
    }

    /** The keys plus, for keys without a batch of batch-tracked variants, the existing batch balances. */
    private Set<BalanceKey> withBatches(Collection<BalanceKey> keys) {
        Set<BalanceKey> all = new TreeSet<>(keys);
        Map<UUID, StockItem> tracked =
                items.findAll(keys.stream().map(BalanceKey::variantId).toList());
        for (BalanceKey key : keys) {
            StockItem item = tracked.get(key.variantId());
            if (!key.hasBatch() && item != null && item.trackBatches()) {
                all.addAll(balances.batchKeys(key.variantId(), key.locationId(), key.stockState()));
            }
        }
        return all;
    }

    /** Rule INV-14: outgoing stock of batch-tracked variants comes from the batches expiring first (FEFO). */
    private List<StockLine> spread(List<StockLine> lines, Map<BalanceKey, BalanceState> locked) {
        Map<UUID, StockItem> tracked =
                items.findAll(lines.stream().map(l -> l.key().variantId()).toList());
        Map<BalanceKey, BigDecimal> left = new HashMap<>();
        locked.forEach((key, state) -> left.put(key, state.onHand()));
        List<StockLine> spread = new ArrayList<>();
        for (StockLine line : lines) {
            StockItem item = tracked.get(line.key().variantId());
            if (line.isInbound() || line.key().hasBatch() || item == null || !item.trackBatches()) {
                spread.add(line);
                continue;
            }
            List<BalanceKey> candidates = fefo(locked.keySet(), line.key());
            BigDecimal wanted = line.qtyBase().negate();
            for (int i = 0; i < candidates.size() && wanted.signum() > 0; i++) {
                BalanceKey batch = candidates.get(i);
                BigDecimal there = left.getOrDefault(batch, BigDecimal.ZERO);
                boolean last = i == candidates.size() - 1;
                BigDecimal take = last ? wanted : wanted.min(there.max(BigDecimal.ZERO));
                if (take.signum() > 0) {
                    spread.add(withKey(line, batch, take.negate()));
                    left.put(batch, there.subtract(take));
                    wanted = wanted.subtract(take);
                }
            }
            if (wanted.signum() > 0) {
                spread.add(withKey(line, line.key(), wanted.negate()));
            }
        }
        return spread;
    }

    private List<BalanceKey> fefo(Collection<BalanceKey> locked, BalanceKey key) {
        List<BalanceKey> candidates = locked.stream()
                .filter(k -> k.hasBatch()
                        && k.variantId().equals(key.variantId())
                        && k.locationId().equals(key.locationId())
                        && k.stockState().equals(key.stockState()))
                .toList();
        Map<UUID, BatchInfo> info =
                batches.findAll(candidates.stream().map(BalanceKey::batchId).toList());
        return candidates.stream()
                .sorted(Comparator.comparing(
                                (BalanceKey k) -> expiry(info.get(k.batchId())),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(k ->
                                Objects.requireNonNull(info.get(k.batchId())).receivedAt())
                        .thenComparing(BalanceKey::batchId))
                .toList();
    }

    private static @Nullable LocalDate expiry(@Nullable BatchInfo batch) {
        return batch == null ? null : batch.expiresOn();
    }

    private static StockLine withKey(StockLine line, BalanceKey key, BigDecimal qtyBase) {
        return new StockLine(
                key,
                line.type(),
                qtyBase,
                line.cost(),
                line.mayGoNegative(),
                line.sourceType(),
                line.sourceId(),
                line.sourceLineId(),
                line.reasonCode(),
                line.occurredAt());
    }

    /** Rule INV-13: after the movements, raise or clear the low flag of each variant and location once. */
    private void evaluateReorder(List<PlannedMovement> movements) {
        Map<List<UUID>, Boolean> places = new LinkedHashMap<>();
        movements.stream()
                .sorted(Comparator.comparing((PlannedMovement m) -> m.key().variantId())
                        .thenComparing(m -> m.key().locationId()))
                .forEach(m -> places.merge(
                        List.of(m.key().variantId(), m.key().locationId()),
                        m.qtyBase().signum() < 0,
                        Boolean::logicalOr));
        Set<List<UUID>> done = new LinkedHashSet<>();
        places.forEach((place, outbound) -> {
            if (!done.add(place)) {
                return;
            }
            UUID variant = place.get(0);
            UUID location = place.get(1);
            reorder.lock(variant, location).ifPresent(policy -> {
                BigDecimal available = balances.available(variant, location);
                switch (ReorderRule.decide(available, policy.reorderLevel(), policy.lowFlag(), outbound)) {
                    case RAISE -> {
                        reorder.flag(variant, location, true);
                        publishLow(policy, available);
                    }
                    case CLEAR -> reorder.flag(variant, location, false);
                    case KEEP -> {
                        // Rule INV-13: one alert per crossing.
                    }
                }
            });
        });
    }

    private void publishLow(ReorderPolicy policy, BigDecimal available) {
        EventStamp stamp = stamps.next();
        publisher.publishEvent(new StockLevelLow(
                stamp.eventId(),
                TenantContext.require(),
                stamp.occurredAt(),
                policy.variantId(),
                policy.version(),
                stamp.metadata(),
                VariantId.of(policy.variantId()),
                LocationId.of(policy.locationId()),
                available,
                policy.reorderLevel(),
                policy.reorderQty(),
                policy.preferredSupplierId()));
        log.atInfo()
                .addKeyValue("variantId", policy.variantId())
                .addKeyValue("locationId", policy.locationId())
                .log("Stock level low");
    }

    private static @Nullable UUID userId() {
        var user = ActorContext.currentOrSystem().userId();
        return user == null ? null : user.value();
    }

    private static @Nullable UUID deviceId() {
        var device = ActorContext.currentOrSystem().deviceId();
        return device == null ? null : device.value();
    }
}
