package com.bmp.web.problem;

import java.util.Objects;

/**
 * A module's i18n bundle (error titles and details). Each module contributes one bean, e.g.
 * {@code new ModuleMessages("i18n/tenant/messages")}, with {@code messages.properties}, {@code _si} and {@code _ta}.
 *
 * @param basename classpath basename of the bundle
 */
public record ModuleMessages(String basename) {

    public ModuleMessages {
        Objects.requireNonNull(basename, "basename");
    }
}
