package com.bmp.sales.application.command;

import com.bmp.sales.domain.port.CustomerStore;
import com.bmp.sales.domain.port.SaleItemStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the catalog and party replicas (version-guarded). */
@Service
public class ReplicaHandlers {

    private final SaleItemStore items;
    private final CustomerStore customers;

    public ReplicaHandlers(SaleItemStore items, CustomerStore customers) {
        this.items = items;
        this.customers = customers;
    }

    @Transactional
    public void variant(SaleItemStore.SaleItem item, long version) {
        items.upsert(item, version);
    }

    @Transactional
    public void variantDeactivated(UUID variantId, long version) {
        items.deactivate(variantId, version);
    }

    @Transactional
    public void customer(CustomerStore.Customer customer, long version) {
        customers.upsert(customer, version);
    }
}
