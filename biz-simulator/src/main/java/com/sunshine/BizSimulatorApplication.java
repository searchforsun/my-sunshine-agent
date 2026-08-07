package com.sunshine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sunshine")
public class BizSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(BizSimulatorApplication.class, args);
    }
}
