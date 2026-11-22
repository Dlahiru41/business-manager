package com.bmp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Assembles every module into the Business Management Platform modular monolith. */
@SpringBootApplication
public class BmpApplication {

    public static void main(String[] args) {
        SpringApplication.run(BmpApplication.class, args);
    }
}
