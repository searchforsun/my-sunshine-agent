package com.sunshine.prompt;

import com.sunshine.common.web.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableDiscoveryClient
@EnableJpaRepositories(basePackages = "com.sunshine.prompt.repo")
@EntityScan(basePackages = "com.sunshine.prompt.entity")
@Import(GlobalExceptionHandler.class)
public class PromptApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromptApplication.class, args);
    }
}
