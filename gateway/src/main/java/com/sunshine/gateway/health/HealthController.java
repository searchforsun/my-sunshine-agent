package com.sunshine.gateway.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Gateway 本地存活探测（不依赖 sunshine-common） */
@RestController
public class HealthController {

    private final String serviceName;

    public HealthController(@Value("${spring.application.name:sunshine-gateway}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", serviceName);
    }
}
