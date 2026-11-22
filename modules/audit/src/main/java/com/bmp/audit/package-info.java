/**
 * Audit module: the tenant's audit trail as data (05-logging-and-observability.md section 9). Records every API write
 * and every domain event in an append-only, hash-chained ledger. It only consumes events, so it depends on no module.
 */
@ApplicationModule(
        displayName = "Audit",
        allowedDependencies = {})
@NullMarked
package com.bmp.audit;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
