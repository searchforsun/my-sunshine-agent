package com.sunshine.sandbox;

import com.sunshine.common.web.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(GlobalExceptionHandler.class)
public class SandboxServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxServiceApplication.class, args);
    }
}
