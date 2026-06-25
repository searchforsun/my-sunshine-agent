package com.sunshine.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/** Sentinel 网关 429 响应；gw-flow 规则由 Nacos datasource 加载（Dashboard 可见）。 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SentinelGatewayTenantConfiguration {

    static final String BLOCK_JSON = "{\"code\":429,\"msg\":\"租户请求过于频繁，请稍后再试\"}";

    private final GatewayTenantQpsProperties properties;

    @PostConstruct
    void init() {
        GatewayCallbackManager.setBlockHandler((exchange, t) -> ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(BLOCK_JSON), String.class));
        if (properties.isEnabled()) {
            log.info("[Gateway] tenant QPS enabled — gw-flow rules from Nacos datasource");
        } else {
            log.warn("[Gateway] tenant QPS disabled (sunshine.gateway.tenant-qps.enabled=false)");
        }
    }
}
