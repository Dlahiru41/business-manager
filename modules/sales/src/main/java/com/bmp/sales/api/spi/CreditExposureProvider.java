package com.bmp.sales.api.spi;

import com.bmp.shared.id.PartyId;
import java.math.BigDecimal;

/**
 * What a customer owes, for the credit limit check (SAL-07). Sales ships a conservative default (Σ CREDIT tenders of
 * the customer's bills, collections ignored); finance provides the real one from receivables in phase 04. Sales never
 * calls payments or finance itself (that would be a dependency cycle).
 */
@FunctionalInterface
public interface CreditExposureProvider {

    BigDecimal exposureOf(PartyId customer);
}
