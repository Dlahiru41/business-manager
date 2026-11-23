package com.bmp.party.api.events;

import java.math.BigDecimal;

/**
 * A party's credit terms (PTY-03) in an event.
 *
 * @param creditLimit in the event's currency
 */
public record CreditPolicySnapshot(boolean creditAllowed, BigDecimal creditLimit, int paymentTermsDays) {}
