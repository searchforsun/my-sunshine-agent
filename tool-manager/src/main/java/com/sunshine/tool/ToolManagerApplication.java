package com.sunshine.tool;

import com.sunshine.common.web.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.sunshine")
@EnableDiscoveryClient
@Import(GlobalExceptionHandler.class)
public class ToolManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolManagerApplication.class, args);
    }
}
