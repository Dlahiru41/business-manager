package com.bmp.inventory.domain.port;

import com.bmp.inventory.domain.model.BalanceKey;
import com.bmp.inventory.domain.model.CostLayer;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** FIFO cost layers (INV-04). */
public interface CostLayerStore {

    /** The layers with stock left of each key, oldest first; call with the balances locked. */
    Map<BalanceKey, List<CostLayer>> load(Collection<BalanceKey> keys);

    /** Stores new and consumed layers: what each key's layers are now. */
    void save(BalanceKey key, List<CostLayer> before, List<CostLayer> after);
}
