package com.bmp.procurement.api.spi;

import com.bmp.shared.id.LocationId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Rule PRO-06: what is at or below its reorder level. Procurement is upstream of inventory, so inventory implements
 * this (dependency inversion, 01 §2); procurement's default knows nothing to reorder.
 */
@FunctionalInterface
public interface ReorderSuggestionProvider {

    /** The suggestions of the current tenant, of one location or all. */
    List<ReorderSuggestion> suggestions(@Nullable LocationId location);
}
