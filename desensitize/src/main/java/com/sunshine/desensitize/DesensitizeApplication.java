package com.sunshine.desensitize;

import com.sunshine.desensitize.config.DesensitizeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = "com.sunshine")
@EnableDiscoveryClient
@EnableConfigurationProperties(DesensitizeProperties.class)
public class DesensitizeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DesensitizeApplication.class, args);
    }
}
