package com.sunshine.tool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "com.sunshine")
@EnableDiscoveryClient
public class ToolManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolManagerApplication.class, args);
    }
}
