package com.bmp.pricing.adapter.in.event;

import com.bmp.persistence.event.InboxGuard;
import com.bmp.pricing.application.command.AdoptTenantCurrencyHandler;
import com.bmp.tenant.api.events.SettingsChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Moves never-priced lists to a changed tenant currency ({@code general.currency}); the inbox makes redelivery harmless. */
@Component
class TenantCurrencyListener {

    static final String CONSUMER = "pricing.TenantCurrencyListener";

    private static final Logger log = LoggerFactory.getLogger(TenantCurrencyListener.class);

    private final InboxGuard inbox;
    private final AdoptTenantCurrencyHandler handler;

    TenantCurrencyListener(InboxGuard inbox, AdoptTenantCurrencyHandler handler) {
        this.inbox = inbox;
        this.handler = handler;
    }

    @ApplicationModuleListener
    void on(SettingsChanged event) {
        if (!event.group().equals("general")) {
            return;
        }
        for (SettingsChanged.Change change : event.changes()) {
            if (change.key().equals("currency")) {
                if (!inbox.firstDelivery(CONSUMER, event.eventId())) {
                    log.debug("Skipping redelivered event eventId={}", event.eventId());
                    return;
                }
                handler.adopt(unquoted(change.value()));
                return;
            }
        }
    }

    /** Setting values are JSON text: {@code "USD"}. */
    static String unquoted(String json) {
        String value = json.strip();
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
