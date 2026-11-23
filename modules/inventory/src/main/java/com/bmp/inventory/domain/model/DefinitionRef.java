package com.bmp.inventory.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A published version of a transformation definition, replicated from the catalog (CAT-10). */
public record DefinitionRef(
        UUID definitionId,
        int defVersion,
        String code,
        String name,
        String kind,
        @Nullable BigDecimal expectedYieldPct,
        List<DefinitionLine> lines,
        boolean active) {

    public DefinitionRef {
        lines = List.copyOf(lines);
    }

    public List<DefinitionLine> inputs() {
        return lines.stream().filter(DefinitionLine::consumes).toList();
    }

    public List<DefinitionLine> outputs() {
        return lines.stream().filter(l -> !l.consumes()).toList();
    }
}
