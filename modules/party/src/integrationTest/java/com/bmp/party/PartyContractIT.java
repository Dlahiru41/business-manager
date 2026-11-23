package com.bmp.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.party.api.events.PartyCreated;
import com.bmp.party.api.events.PartyUpdated;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** Party keeps its contracts: every operation protected as x-permission says; its events as AsyncAPI says. */
@SpringBootTest(classes = PartyTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class PartyContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        PartyApi api = new PartyApi(mvc, "Party security");
        String nobody =
                JwtTestTokens.builder().tenant(api.tenant).roles("NOBODY").build();
        ContractSecurity.assertEveryOperationIsProtected("party", mvc, context, nobody);
    }

    @Test
    void partyEventsConformToTheAsyncApiContract() {
        PartyApi api = new PartyApi(mvc, "Party events");
        String path =
                "/api/v1/parties/" + api.party(PartyApiIT.SUPPLIER).path("id").asString();
        api.put(
                path + "/credit-policy",
                "{\"creditAllowed\": true, \"creditLimit\": \"1000.00\", \"paymentTermsDays\": 30}",
                PartyApi.etag(api.get(path)));

        assertThat(events.stream(PartyCreated.class)).isNotEmpty();
        assertThat(events.stream(PartyUpdated.class)).isNotEmpty();
        events.stream(PartyCreated.class)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "PartyCreated"));
        events.stream(PartyUpdated.class)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "PartyUpdated"));
    }
}
