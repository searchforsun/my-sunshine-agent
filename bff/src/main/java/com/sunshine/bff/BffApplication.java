package com.sunshine.bff;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.common.web.RemoteErrorMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.sunshine")
@EnableDiscoveryClient
@Import(GlobalExceptionHandler.class)
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
