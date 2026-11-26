package com.bmp.printagent.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The agent's settings, from {@code agent.yaml} next to the agent or in {@code ~/bmp-print-agent/}. The local token and
 * the device secret are never printed: {@link #toString()} leaves them out.
 *
 * @param localToken the shared token the till sends as {@code Authorization: Bearer ...}; at least 16 characters
 * @param dataDir where the queue, the cached template and the logs live
 * @param shopName heads receipts until the cloud's template has been fetched once
 * @param paperWidthMm 58 or 80, until the cloud's template says otherwise
 * @param allowedOrigins browser origins of the till allowed to call the local API (CORS); empty for none
 */
@ConfigurationProperties("agent")
public record AgentProperties(
        String localToken,
        Path dataDir,
        String shopName,
        int paperWidthMm,
        List<String> allowedOrigins,
        Printer printer,
        Queue queue,
        Cloud cloud) {

    private static final int MIN_TOKEN_LENGTH = 16;

    public AgentProperties {
        if (localToken == null || localToken.strip().length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    "agent.local-token must be set at install, at least " + MIN_TOKEN_LENGTH + " characters");
        }
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    /** How the printer is reached. */
    public enum PrinterType {
        /** Raw TCP, usually port 9100 (network printers). */
        TCP,
        /** An operating system print queue by name (USB printers), through {@code javax.print}. */
        QUEUE,
        /** A serial or USB-serial port, through jSerialComm. */
        SERIAL
    }

    /**
     * The printer.
     *
     * @param queueName the OS print queue, for {@link PrinterType#QUEUE}
     * @param serialPort e.g. {@code COM3} or {@code /dev/ttyUSB0}, for {@link PrinterType#SERIAL}
     * @param timeout connect and write timeout
     */
    public record Printer(
            PrinterType type,
            @Nullable String host,
            int port,
            @Nullable String queueName,
            @Nullable String serialPort,
            int baudRate,
            Duration timeout) {}

    /**
     * The local queue.
     *
     * @param pollInterval how often queued requests are looked at
     * @param maxBackoff the longest wait between two attempts of one request
     * @param cloudMaxAttempts attempts after which a reprint from the cloud is reported FAILED; the till's own requests
     *     are kept until printed
     * @param retention how long printed requests stay on disk
     */
    public record Queue(Duration pollInterval, Duration maxBackoff, int cloudMaxAttempts, Duration retention) {}

    /**
     * The link to the cloud, with the device credential (client credentials of the shop PC's device).
     *
     * @param agentId the print agent registered for this PC ({@code POST /api/v1/print-agents})
     * @param waitSeconds how long one long poll for jobs waits (at most 30)
     * @param maxBackoff the longest wait between two attempts to reach the cloud
     * @param templateRefresh how often the template is fetched again
     */
    public record Cloud(
            boolean enabled,
            @Nullable URI baseUrl,
            @Nullable UUID agentId,
            @Nullable URI tokenUrl,
            @Nullable String clientId,
            @Nullable String clientSecret,
            int waitSeconds,
            Duration maxBackoff,
            Duration templateRefresh) {

        @Override
        public String toString() {
            return "Cloud[enabled=" + enabled + ", baseUrl=" + baseUrl + ", agentId=" + agentId + ", tokenUrl="
                    + tokenUrl + ", clientId=" + clientId + ", clientSecret=***]";
        }
    }

    @Override
    public String toString() {
        return "AgentProperties[localToken=***, dataDir=" + dataDir + ", shopName=" + shopName + ", paperWidthMm="
                + paperWidthMm + ", printer=" + printer + ", queue=" + queue + ", cloud=" + cloud + "]";
    }
}
