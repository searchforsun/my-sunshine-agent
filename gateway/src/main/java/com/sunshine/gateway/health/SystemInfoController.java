package com.sunshine.gateway.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 宿主机基本资源信息：GET /health/server → {status, service, data}。 */
@RestController
public class SystemInfoController {

    private final String serviceName;

    public SystemInfoController(@Value("${spring.application.name:sunshine-gateway}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping("/health/server")
    public Map<String, Object> server() {
        return Map.of(
                "status", "UP",
                "service", serviceName,
                "data", SystemInfoProvider.collect());
    }
}
