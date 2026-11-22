package com.bmp.catalog.domain.port;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a keyset page starts: after the item with this sort key and id.
 *
 * @param sortKey the sort column value of the last item of the previous page (code, SKU or name)
 * @param id its id, the tie-breaker
 */
public record PageKey(String sortKey, UUID id) {

    public PageKey {
        Objects.requireNonNull(sortKey, "sortKey");
        Objects.requireNonNull(id, "id");
    }
}
