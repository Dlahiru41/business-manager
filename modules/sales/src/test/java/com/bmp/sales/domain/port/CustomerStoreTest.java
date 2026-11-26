package com.bmp.sales.domain.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.sales.domain.port.CustomerStore.Customer;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerStoreTest {

    private static Customer party(String type) {
        return new Customer(UUID.randomUUID(), type, "Hotel Lake View", null, true, true, BigDecimal.TEN);
    }

    @Test
    void customersAndPartiesThatAreBothMayBuyButSuppliersMayNot() {
        assertThat(party("CUSTOMER").isCustomer()).isTrue();
        assertThat(party("BOTH").isCustomer()).isTrue();
        assertThat(party("SUPPLIER").isCustomer()).isFalse();
    }
}
