package com.bmp.inventory.application.command;

import com.bmp.inventory.domain.model.DefinitionRef;
import com.bmp.inventory.domain.model.LocationRef;
import com.bmp.inventory.domain.model.StockItem;
import com.bmp.inventory.domain.port.DefinitionStore;
import com.bmp.inventory.domain.port.LocationStore;
import com.bmp.inventory.domain.port.StockItemStore;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the replicas of catalog variants, products and definitions and of tenant locations (T02-03). */
@Service
public class ReplicateCatalogHandler {

    private final StockItemStore items;
    private final DefinitionStore definitions;
    private final LocationStore locations;

    public ReplicateCatalogHandler(StockItemStore items, DefinitionStore definitions, LocationStore locations) {
        this.items = items;
        this.definitions = definitions;
        this.locations = locations;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void variant(StockItem snapshot) {
        items.upsert(snapshot);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void variantDeactivated(UUID variantId, UUID productId, String sku, long aggregateVersion) {
        items.deactivate(variantId, productId, sku, aggregateVersion);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void product(UUID productId, String name, @Nullable UUID categoryId, long aggregateVersion) {
        items.upsertProduct(productId, name, categoryId, aggregateVersion);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void definitionPublished(DefinitionRef definition) {
        definitions.upsert(definition);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void definitionRetired(UUID definitionId, int defVersion) {
        definitions.retire(definitionId, defVersion);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void location(LocationRef location) {
        locations.upsert(location);
    }
}
