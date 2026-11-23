package com.bmp.inventory.domain.policy;

import com.bmp.inventory.domain.model.BalanceState;
import java.math.BigDecimal;

/**
 * The cost of one movement and the balance after it.
 *
 * @param cost signed like the movement's quantity (INV-02)
 * @param correction value added (or removed) to true the balance up after negative stock (INV-03, INV-04); 0 if none
 */
public record Costed(BigDecimal cost, BigDecimal correction, BalanceState next) {}
