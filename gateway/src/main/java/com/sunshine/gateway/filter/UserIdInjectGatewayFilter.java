package com.sunshine.gateway.filter;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class UserIdInjectGatewayFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/api/") || shouldSkip(path)) {
            return chain.filter(exchange);
        }
        SaReactorSyncHolder.setContext(exchange);
        return Mono.defer(() -> {
            if (!StpUtil.isLogin()) {
                return chain.filter(exchange);
            }
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove("x-user-id");
                        headers.remove("x-tenant-id");
                        headers.set("x-user-id", StpUtil.getLoginIdAsString());
                        headers.set("x-tenant-id", "default");
                    })
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        }).doFinally(signal -> SaReactorSyncHolder.clearContext());
    }

    static boolean shouldSkip(String path) {
        return isPublicAuthPath(path) || path.startsWith("/api/auth/");
    }

    static boolean isPublicAuthPath(String path) {
        return "/api/auth/login".equals(path) || "/api/auth/register".equals(path);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
