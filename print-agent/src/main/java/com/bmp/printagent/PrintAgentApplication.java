package com.bmp.printagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Starts the print agent: the local API on 127.0.0.1:9123, the print worker and the cloud link. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PrintAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrintAgentApplication.class, args);
    }
}
