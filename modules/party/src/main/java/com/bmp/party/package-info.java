/**
 * Party module: the customers and suppliers of a tenant, their customer groups and credit terms (phase 02,
 * PTY-01..PTY-04). Personal data stays in this module; events carry ids, names, groups and credit terms only.
 */
@ApplicationModule(
        displayName = "Party",
        allowedDependencies = {"tenant :: api"})
@NullMarked
package com.bmp.party;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
