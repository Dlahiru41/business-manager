/**
 * Reporting module: read models built only from events and rebuildable from its own event log (REP-01), gross margin
 * per bill and line, sales, stock, wastage, cash and aging reports, the owner dashboard, data-quality monitors and
 * exports (phase 04, REP-01..REP-07). Overall profit and aging come from finance's API; nothing here recomputes
 * accounting.
 */
@ApplicationModule(
        displayName = "Reporting",
        allowedDependencies = {
            "tenant :: api",
            "catalog :: api",
            "inventory :: api",
            "sales :: api",
            "payments :: api",
            "finance :: api",
            "sync :: api"
        })
@NullMarked
package com.bmp.reporting;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
