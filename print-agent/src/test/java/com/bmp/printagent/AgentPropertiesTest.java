package com.bmp.printagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.printagent.config.AgentProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentPropertiesTest {

    private static AgentProperties settings(String token) {
        return new AgentProperties(
                token,
                Path.of("data"),
                "Shop",
                80,
                null,
                new AgentProperties.Printer(
                        AgentProperties.PrinterType.TCP, "127.0.0.1", 9100, null, null, 9600, Duration.ofSeconds(2)),
                new AgentProperties.Queue(Duration.ofSeconds(2), Duration.ofSeconds(60), 5, Duration.ofDays(7)),
                new AgentProperties.Cloud(
                        true,
                        URI.create("https://bmp.example.com"),
                        UUID.randomUUID(),
                        URI.create("https://auth.example.com/token"),
                        "device-1",
                        "device-secret-value",
                        25,
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(5)));
    }

    @Test
    void theAgentDoesNotStartWithoutALocalTokenOfSixteenCharacters() {
        assertThatThrownBy(() -> settings("short")).hasMessageContaining("agent.local-token");
        assertThatThrownBy(() -> settings("                    ")).hasMessageContaining("agent.local-token");
        assertThat(settings("0123456789abcdef").allowedOrigins()).isEqualTo(List.of());
    }

    @Test
    void secretsNeverAppearWhenTheSettingsArePrinted() {
        String printed = settings("local-token-of-the-shop").toString();

        assertThat(printed).doesNotContain("local-token-of-the-shop", "device-secret-value");
        assertThat(printed).contains("clientSecret=***", "localToken=***", "device-1");
    }
}
