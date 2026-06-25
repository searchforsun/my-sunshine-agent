package com.sunshine.gateway.filter;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import com.sunshine.gateway.tenant.TenantIdResolver;
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
            boolean loggedIn = StpUtil.isLogin();
            ServerHttpRequest.Builder builder = exchange.getRequest().mutate();
            builder.headers(headers -> {
                headers.remove("x-user-id");
                headers.remove("x-user-name");
                headers.remove("x-tenant-id");
                headers.set("x-tenant-id", TenantIdResolver.forRateLimit(loggedIn));
                if (loggedIn) {
                    headers.set("x-user-id", StpUtil.getLoginIdAsString());
                    Object nickname = StpUtil.getExtra("nickname");
                    if (nickname != null && !String.valueOf(nickname).isBlank()) {
                        headers.set("x-user-name", String.valueOf(nickname).trim());
                    }
                }
            });
            return chain.filter(exchange.mutate().request(builder.build()).build());
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
        // 早于 Sentinel SCG 过滤器注入 x-tenant-id，供热点参数分桶
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
