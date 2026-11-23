package com.bmp.inventory.domain.policy;

/** Marks costing policies that keep cost layers, which the ledger must load and store (INV-04). */
public sealed interface FifoCostingMarker permits FifoCosting {}
