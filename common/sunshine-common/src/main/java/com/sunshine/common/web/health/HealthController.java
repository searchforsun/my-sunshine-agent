package com.sunshine.common.web.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 统一存活探测：GET /health → {"status":"UP","service":"..."} */
@RestController
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name:unknown}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", serviceName);
    }
}
