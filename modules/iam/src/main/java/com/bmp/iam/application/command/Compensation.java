package com.bmp.iam.application.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Undoes an identity provider change after the local transaction failed. A failed compensation is logged for an
 * operator and never hides the original error.
 */
final class Compensation {

    private static final Logger log = LoggerFactory.getLogger(Compensation.class);

    private Compensation() {}

    static void run(String action, String resourceId, Runnable undo) {
        try {
            undo.run();
            log.atInfo()
                    .addKeyValue("action", action)
                    .log("Compensated identity provider change action={} resource={}", action, resourceId);
        } catch (RuntimeException e) {
            log.atError()
                    .addKeyValue("action", action)
                    .setCause(e)
                    .log(
                            "Identity provider change could not be undone, reconcile manually action={} resource={}",
                            action,
                            resourceId);
        }
    }
}
