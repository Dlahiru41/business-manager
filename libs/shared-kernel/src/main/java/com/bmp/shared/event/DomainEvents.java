package com.bmp.shared.event;

import java.util.Locale;
import java.util.regex.Pattern;

/** Naming conventions for events (07-events-and-messaging.md §1). */
public final class DomainEvents {

    private static final Pattern EVENT_PACKAGE = Pattern.compile("com\\.bmp\\.([a-z]+)\\.api\\.events");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private DomainEvents() {}

    /**
     * The externalized type name {@code bmp.<module>.<event-kebab>.v1}, e.g. {@code bmp.sales.sale-completed.v1}.
     * Events must live in {@code com.bmp.<module>.api.events}; a class name ending in {@code V<n>} sets the version.
     */
    public static String typeName(Class<? extends DomainEvent> eventType) {
        var matcher = EVENT_PACKAGE.matcher(eventType.getPackageName());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Events must live in com.bmp.<module>.api.events: " + eventType);
        }
        String simpleName = eventType.getSimpleName();
        int version = 1;
        var versioned = Pattern.compile("(.+)V(\\d+)").matcher(simpleName);
        if (versioned.matches()) {
            simpleName = versioned.group(1);
            version = Integer.parseInt(versioned.group(2));
        }
        String kebab = CAMEL_BOUNDARY.matcher(simpleName).replaceAll("-").toLowerCase(Locale.ROOT);
        return "bmp." + matcher.group(1) + "." + kebab + ".v" + version;
    }
}
