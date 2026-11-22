package com.bmp.catalog.application.command;

import com.bmp.catalog.application.query.UnitView;
import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.Dimension;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.port.CatalogRevisions;
import com.bmp.catalog.domain.port.UnitRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds tenant units of measure (CAT-01); their codes never repeat a standard or own unit code. */
@Service
public class UnitHandlers {

    private final UnitRepository units;
    private final CatalogRevisions revisions;
    private final IdGenerator ids;

    public UnitHandlers(UnitRepository units, CatalogRevisions revisions, IdGenerator ids) {
        this.units = units;
        this.revisions = revisions;
        this.ids = ids;
    }

    public record CreateUnit(
            String code,
            String name,
            Dimension dimension,
            @Nullable BigDecimal toSiFactor) {}

    @Transactional
    @Observed(name = "bmp.catalog.create-unit")
    public UnitView create(CreateUnit command) {
        if (units.findByCode(command.code()).isPresent()) {
            throw new BusinessRuleViolation(CatalogErrorCode.UNIT_CODE_TAKEN, Map.of("code", command.code()));
        }
        UnitOfMeasure unit = UnitOfMeasure.custom(
                ids.newUuid(),
                TenantContext.require().value(),
                command.code(),
                command.name(),
                command.dimension(),
                command.toSiFactor());
        units.insert(unit);
        revisions.next();
        return UnitView.of(unit);
    }
}
