package com.bmp.inventory.domain.model;

import java.util.UUID;

/** A location of the tenant, replicated from {@code LocationCreated}. */
public record LocationRef(UUID locationId, String name, String type, boolean active) {}
