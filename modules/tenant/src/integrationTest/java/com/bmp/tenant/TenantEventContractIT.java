package com.bmp.tenant;

import static com.bmp.tenant.TenantApi.etag;
import static com.bmp.tenant.TenantApi.token;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.tenant.api.events.LocationCreated;
import com.bmp.tenant.api.events.SettingsChanged;
import com.bmp.tenant.api.events.TenantCreated;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** Every tenant event, serialized as the outbox stores it, conforms to contracts/asyncapi/events.yaml. */
@SpringBootTest(classes = TenantTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class TenantEventContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    @Test
    void publishedEventsConformToTheAsyncApiContract() {
        TenantApi api = new TenantApi(mvc);
        TenantId tenant = api.createTenant("Contract " + UUID.randomUUID());
        String owner = token(tenant, SystemRole.OWNER);
        api.patch(
                "/api/v1/settings/sales",
                owner,
                "{\"values\": {\"returnWindowDays\": 10}}",
                etag(api.get("/api/v1/settings/sales", owner)));

        var created = events.stream(TenantCreated.class)
                .filter(e -> e.tenantId().equals(tenant))
                .toList();
        var located = events.stream(LocationCreated.class)
                .filter(e -> e.tenantId().equals(tenant))
                .toList();
        var changed = events.stream(SettingsChanged.class)
                .filter(e -> e.tenantId().equals(tenant))
                .toList();

        assertThat(created).hasSize(1);
        assertThat(located).hasSize(1);
        assertThat(changed).hasSize(1);
        created.forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "TenantCreated"));
        located.forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "LocationCreated"));
        changed.forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "SettingsChanged"));
    }
}
