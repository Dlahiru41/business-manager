package com.bmp.catalog.application;

import com.bmp.catalog.domain.error.CatalogErrorCode;
import com.bmp.catalog.domain.model.UnitOfMeasure;
import com.bmp.catalog.domain.port.UnitRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The units of measure the current tenant sees, loaded once per use case: resolves the unit codes of requests to
 * units and the unit ids of aggregates back to codes.
 */
public final class UnitDirectory {

    private final List<UnitOfMeasure> units;
    private final Map<UUID, UnitOfMeasure> byId = new HashMap<>();
    private final Map<String, UnitOfMeasure> byCode = new HashMap<>();

    private UnitDirectory(List<UnitOfMeasure> units) {
        this.units = List.copyOf(units);
        for (UnitOfMeasure unit : units) {
            byId.put(unit.id(), unit);
            // Standard units come first, so a code is never shadowed by a tenant unit.
            byCode.putIfAbsent(unit.code().toLowerCase(Locale.ROOT), unit);
        }
    }

    public static UnitDirectory load(UnitRepository repository) {
        return new UnitDirectory(repository.findAll());
    }

    public List<UnitOfMeasure> all() {
        return units;
    }

    /** The unit with this code (case-insensitive), or {@code UNKNOWN_UNIT}. */
    public UnitOfMeasure byCode(String code) {
        UnitOfMeasure unit = byCode.get(code.toLowerCase(Locale.ROOT));
        if (unit == null) {
            throw new BusinessRuleViolation(CatalogErrorCode.UNKNOWN_UNIT, Map.of("unit", code));
        }
        return unit;
    }

    /** The code of a unit an aggregate refers to. */
    public String codeOf(UUID unitId) {
        UnitOfMeasure unit = byId.get(unitId);
        if (unit == null) {
            throw new BusinessRuleViolation(CatalogErrorCode.UNKNOWN_UNIT, Map.of("unitId", unitId));
        }
        return unit.code();
    }
}
