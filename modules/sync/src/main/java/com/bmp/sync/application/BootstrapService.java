package com.bmp.sync.application;

import com.bmp.catalog.api.CatalogQueries;
import com.bmp.documents.api.DocumentsQueries;
import com.bmp.inventory.api.InventoryQueries;
import com.bmp.party.api.PartyQueries;
import com.bmp.pricing.api.PricingQueries;
import com.bmp.sales.api.SalesQueries;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.id.RegisterId;
import com.bmp.sync.domain.model.DeviceState;
import com.bmp.sync.domain.port.SyncStates;
import com.bmp.sync.domain.service.ClockSkew;
import com.bmp.sync.domain.service.NumberGaps;
import com.bmp.tenant.api.TenantQueries;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rules SYN-02, SYN-03, SYN-07: the data a till works offline with, each dataset with a version, and what changed
 * since the versions a till holds. A version is a digest of the dataset, so it changes exactly when the data does;
 * a changed dataset is sent whole (ADR-029).
 */
@Service
public class BootstrapService {

    /** The datasets a till holds. */
    public static final List<String> DATASETS =
            List.of("catalog", "priceLists", "taxRates", "settings", "receiptTemplate", "customers", "stockBalances");

    private static final Map<String, List<String>> SETTINGS = Map.of(
            "general",
            List.of("currency", "timeZone", "businessDayCutoffHour", "languages"),
            "sales",
            List.of(
                    "maxCashierDiscountPct",
                    "returnWindowDays",
                    "voidRequiresManager",
                    "allowCreditSales",
                    "offlineMaxHours",
                    "rounding",
                    "wholesaleRequiresCustomer",
                    "tenderReferenceRequired"),
            "pricing",
            List.of("pricesTaxInclusive", "derivePriceFromBase", "cashierCanOverridePrice"));

    private final SyncDevices devices;
    private final CatalogQueries catalog;
    private final PricingQueries pricing;
    private final PartyQueries parties;
    private final InventoryQueries inventory;
    private final TenantQueries tenants;
    private final SalesQueries sales;
    private final DocumentsQueries documents;
    private final SyncStates states;
    private final JsonMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @SuppressWarnings("TooManyParameters") // the modules a till's data comes from
    public BootstrapService(
            SyncDevices devices,
            CatalogQueries catalog,
            PricingQueries pricing,
            PartyQueries parties,
            InventoryQueries inventory,
            TenantQueries tenants,
            SalesQueries sales,
            DocumentsQueries documents,
            SyncStates states,
            JsonMapper json,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.devices = devices;
        this.catalog = catalog;
        this.pricing = pricing;
        this.parties = parties;
        this.inventory = inventory;
        this.tenants = tenants;
        this.sales = sales;
        this.documents = documents;
        this.states = states;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** A dataset and its version. */
    public record Dataset(String version, Object data) {}

    /** What a till receives: its register and the datasets. */
    public record Snapshot(SyncDevices.Till till, Map<String, Dataset> datasets, Instant serverTime) {}

    /** A customer as a till needs it; no contact details. */
    public record Customer(
            UUID id, String displayName, @Nullable UUID customerGroupId, boolean creditAllowed, String creditLimit) {}

    /** Stock a till may sell, in base units. */
    public record Stock(UUID variantId, String stockState, String available) {}

    /** What sync knows of the calling till. */
    public record Status(SyncDevices.Till till, Optional<DeviceState> state, List<String> missingNumbers) {}

    /** Rule SYN-02. */
    public Snapshot bootstrap(@Nullable Instant deviceTime) {
        SyncDevices.Till till = devices.current();
        Map<String, Dataset> datasets = new LinkedHashMap<>();
        for (String name : DATASETS) {
            datasets.put(name, load(name, till));
        }
        seen(till, true, deviceTime);
        return new Snapshot(till, datasets, clock.instant());
    }

    /** Rule SYN-03: the datasets whose version differs from the one the till holds. */
    public Snapshot changes(String cursors, @Nullable Instant deviceTime) {
        SyncDevices.Till till = devices.current();
        Map<String, Dataset> changed = new LinkedHashMap<>();
        parse(cursors).forEach((name, version) -> {
            Dataset now = load(name, till);
            if (!now.version().equals(version)) {
                changed.put(name, now);
            }
        });
        seen(till, false, deviceTime);
        return new Snapshot(till, changed, clock.instant());
    }

    public Status status() {
        SyncDevices.Till till = devices.current();
        List<String> missing = Objects.requireNonNull(transaction.execute(s ->
                NumberGaps.newGaps(sales.billSequences(RegisterId.of(till.registerId())), Set.of()).stream()
                        .map(n -> till.billPrefix() + "-" + "%06d".formatted(n))
                        .toList()));
        Optional<DeviceState> state = Objects.requireNonNull(transaction.execute(s -> states.device(till.deviceId())));
        return new Status(till, state, missing);
    }

    private Dataset load(String name, SyncDevices.Till till) {
        Object data = switch (name) {
            case "catalog" -> catalog.tillSnapshot();
            case "priceLists" -> pricing.tillPriceLists();
            case "taxRates" -> pricing.tillTaxRates();
            case "settings" -> settings();
            case "receiptTemplate" -> documents.receiptTemplate();
            case "customers" ->
                parties.activeCustomers().stream()
                        .map(c -> new Customer(
                                c.id().value(),
                                c.displayName(),
                                c.customerGroupId(),
                                c.creditAllowed(),
                                plain(c.creditLimit())))
                        .toList();
            case "stockBalances" ->
                inventory.available(till.locationId()).stream()
                        .map(a -> new Stock(a.variantId(), a.stockState(), plain(a.available())))
                        .toList();
            default -> throw new IllegalStateException("Unknown dataset " + name);
        };
        return new Dataset(version(data), data);
    }

    private Map<String, Map<String, Object>> settings() {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        SETTINGS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(group -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : group.getValue()) {
                tenants.setting(group.getKey(), key).ifPresent(v -> values.put(key, json.readTree(v)));
            }
            groups.put(group.getKey(), values);
        });
        return groups;
    }

    private String version(Object data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static Map<String, String> parse(String cursors) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : cursors.split(",", -1)) {
            int colon = pair.indexOf(':');
            String name = colon < 0 ? pair.strip() : pair.substring(0, colon).strip();
            if (colon < 0 || !DATASETS.contains(name)) {
                throw new BusinessRuleViolation(
                        PlatformErrorCode.INVALID_PARAMETER, "Unknown dataset cursor", Map.of("parameter", "cursors"));
            }
            parsed.put(name, pair.substring(colon + 1).strip());
        }
        return parsed;
    }

    private void seen(SyncDevices.Till till, boolean bootstrap, @Nullable Instant deviceTime) {
        Instant now = clock.instant();
        Long skew = deviceTime == null ? null : ClockSkew.seconds(deviceTime, now);
        transaction.executeWithoutResult(s -> states.seen(till.deviceId(), till.registerId(), bootstrap, skew, now));
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
