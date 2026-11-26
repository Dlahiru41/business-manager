/**
 * Sync module: offline tills bootstrap their data, fetch what changed, and upload what they did while offline as one
 * ordered batch, each record processed once in its own transaction through the owning module's API (phase 03,
 * SYN-01..SYN-08). It detects bill number gaps and device clock skew.
 */
@ApplicationModule(
        displayName = "Sync",
        allowedDependencies = {
            "tenant :: api",
            "iam :: api",
            "catalog :: api",
            "pricing :: api",
            "party :: api",
            "sales :: api",
            "payments :: api",
            "inventory :: api"
        })
@NullMarked
package com.bmp.sync;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
