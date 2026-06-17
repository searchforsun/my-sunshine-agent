package com.sunshine.desensitize;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "com.sunshine")
@EnableDiscoveryClient
public class DesensitizeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DesensitizeApplication.class, args);
    }
}
