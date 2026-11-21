/** Public API of the audit module: other modules record explicit audit entries through {@code AuditWriter}. */
@NamedInterface(name = "api", propagate = true)
@NullMarked
package com.bmp.audit.api;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
