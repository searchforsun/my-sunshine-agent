package com.sunshine.gateway.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * CORS 预检：Sa-Token 等过滤器可能先于 Gateway globalcors 拦截 OPTIONS，
 * 此处仅处理 preflight；实际响应的 Access-Control-* 由 Nacos globalcors 统一注入。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevCorsWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = response.getHeaders();
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        headers.set(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        String requestHeaders = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders != null ? requestHeaders : "*");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS");
        headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
        response.setStatusCode(HttpStatus.OK);
        return response.setComplete();
    }
}
