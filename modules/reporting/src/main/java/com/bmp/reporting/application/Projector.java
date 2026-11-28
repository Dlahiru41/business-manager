package com.bmp.reporting.application;

import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.reporting.domain.model.Fact;
import com.bmp.reporting.domain.port.ReadModels;
import com.bmp.reporting.domain.service.Entries;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Applies a fact to the read models (ADR-033 §2–§4), live and in a rebuild alike: the sale half and the cost half of
 * a line meet whichever comes first, and the day's summary gets the cost when both are there.
 */
@Component
public class Projector {

    private final ReadModels models;

    public Projector(ReadModels models) {
        this.models = models;
    }

    /** Applies the fact; {@code eventId} lets a void wait for its sale. */
    public void apply(@Nullable UUID eventId, Fact fact) {
        switch (fact) {
            case Fact.Sale s -> sale(s);
            case Fact.Return r -> returned(r);
            case Fact.Voided v -> voided(eventId, v);
            case Fact.Cost c -> cost(c);
            case Fact.Payment p -> payment(p);
            case Fact.Expense e -> {
                models.expense(e);
                models.summarize(e.businessDate(), NO_LOCATION, BigDecimal.ZERO, BigDecimal.ZERO, e.amount(), 0);
            }
            case Fact.Drawer d -> models.drawer(d);
            case Fact.Movement m -> models.movement(m);
            case Fact.VariantNamed v -> models.variant(v);
            case Fact.ProductNamed p -> models.product(p);
        }
    }

    /** Expenses belong to the shop, not a location: they are summarized under the nil location. */
    public static final UUID NO_LOCATION = new UUID(0, 0);

    private void sale(Fact.Sale s) {
        models.bill(s);
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (Fact.SaleLine l : s.lines()) {
            Optional<BigDecimal> early = models.saleHalf(new ReadModels.SaleHalf(
                    Entries.sold(l.lineId()),
                    "SALE",
                    s.billId(),
                    l.lineId(),
                    s.billId(),
                    s.businessDate(),
                    s.locationId(),
                    s.channel(),
                    s.customerId(),
                    s.cashierId(),
                    l.variantId(),
                    l.packagingUnitId(),
                    l.qty(),
                    l.qtyBase(),
                    l.revenue(),
                    l.discount(),
                    l.tax()));
            revenue = revenue.add(l.revenue());
            cost = cost.add(early.orElse(BigDecimal.ZERO));
        }
        models.summarize(s.businessDate(), s.locationId(), revenue, cost, BigDecimal.ZERO, 1);
    }

    private void returned(Fact.Return r) {
        models.returnWatch(r);
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (Fact.ReturnLine l : r.lines()) {
            Optional<BigDecimal> early = models.saleHalf(new ReadModels.SaleHalf(
                    Entries.returned(r.creditNoteId(), l.billLineId()),
                    "RETURN",
                    r.billId(),
                    l.billLineId(),
                    r.creditNoteId(),
                    r.businessDate(),
                    r.locationId(),
                    null,
                    null,
                    null,
                    l.variantId(),
                    null,
                    l.qty().negate(),
                    l.qtyBase().negate(),
                    l.revenue().negate(),
                    BigDecimal.ZERO,
                    l.tax().negate()));
            revenue = revenue.subtract(l.revenue());
            cost = cost.add(early.orElse(BigDecimal.ZERO));
        }
        models.summarize(r.businessDate(), r.locationId(), revenue, cost, BigDecimal.ZERO, 0);
    }

    private void voided(@Nullable UUID eventId, Fact.Voided v) {
        if (!models.billKnown(v.billId())) {
            if (eventId == null) {
                return; // a rebuild replays in recorded order: the sale was never recorded
            }
            throw new OutOfOrderEventException(eventId, "the sale of bill " + v.billId());
        }
        models.voidBill(v.billId(), v.businessDate());
        UUID location = models.billLocation(v.billId()).orElse(NO_LOCATION);
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (ReadModels.SoldLine l : models.soldLines(v.billId())) {
            Optional<BigDecimal> early = models.saleHalf(new ReadModels.SaleHalf(
                    Entries.voided(l.billLineId()),
                    "VOID",
                    v.billId(),
                    l.billLineId(),
                    v.billId(),
                    v.businessDate(),
                    location,
                    null,
                    null,
                    null,
                    l.variantId(),
                    l.packagingUnitId(),
                    l.qty().negate(),
                    l.qtyBase().negate(),
                    l.revenue().negate(),
                    l.discount().negate(),
                    l.tax().negate()));
            revenue = revenue.subtract(l.revenue());
            cost = cost.add(early.orElse(BigDecimal.ZERO));
        }
        models.summarize(v.businessDate(), location, revenue, cost, BigDecimal.ZERO, 0);
    }

    private void cost(Fact.Cost c) {
        for (Fact.LineCost l : c.lines()) {
            models.costHalf(
                            Entries.ofCost(c.kind(), c.sourceId(), l.billLineId()),
                            kind(c.kind()),
                            c.billId(),
                            l.billLineId(),
                            c.sourceId(),
                            l.cost())
                    .ifPresent(placed -> models.summarize(
                            placed.businessDate(), placed.locationId(), BigDecimal.ZERO, l.cost(), BigDecimal.ZERO, 0));
        }
    }

    private void payment(Fact.Payment p) {
        models.payment(p);
        if (p.kind().equals("REFUND") && p.creditNoteId() != null) {
            models.refundSeen(p.creditNoteId(), p.amount().negate());
        }
    }

    private static String kind(String stockKind) {
        return switch (stockKind) {
            case "SALE_RETURN" -> "RETURN";
            case "SALE_VOID" -> "VOID";
            default -> "SALE";
        };
    }
}
