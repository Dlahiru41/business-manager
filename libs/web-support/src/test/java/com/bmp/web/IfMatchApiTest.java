package com.bmp.web;

import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.web.testapp.DummyController;
import com.bmp.web.testapp.WebSupportTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(classes = WebSupportTestApplication.class)
@AutoConfigureMockMvc
class IfMatchApiTest {

    private static final String BODY =
            "{\"name\": \"Eggs\", \"price\": {\"amount\": \"62.50\", \"currency\": \"LKR\"}}";

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult patch(String ifMatch) {
        var request = mvc.patch()
                .uri("/test/things/{id}", DummyController.THING_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
        return (ifMatch == null ? request : request.header("If-Match", ifMatch)).exchange();
    }

    @Test
    void shouldRequireIfMatchWith428() {
        assertThatProblem(patch(null)).hasStatus(428).hasCode("PRECONDITION_REQUIRED");
        assertThatProblem(patch("*")).hasStatus(428).hasCode("PRECONDITION_REQUIRED");
        assertThatProblem(patch("\"not-a-version\"")).hasStatus(428).hasCode("PRECONDITION_REQUIRED");
    }

    @Test
    void shouldRejectStaleVersionWith412AndCurrentVersion() {
        assertThatProblem(patch("W/\"2\""))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT")
                .hasDetail("currentVersion", "3");
    }

    @Test
    void shouldAcceptCurrentVersionAndReturnNewEtag() {
        assertThat(patch("W/\"3\"")).hasStatusOk().hasHeader("ETag", "W/\"4\"");
        assertThat(patch("\"3\"")).hasStatusOk();
    }
}
