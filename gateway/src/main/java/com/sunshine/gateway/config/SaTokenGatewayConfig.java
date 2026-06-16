package com.sunshine.gateway.config;

import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SaTokenGatewayConfig {

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/api/**")
                .addExclude("/api/auth/login", "/api/auth/register")
                .setAuth(obj -> SaRouter.match("/api/**", r -> StpUtil.checkLogin()))
                .setError(e -> SaResult.error("未登录或 Token 已失效").setCode(401));
    }
}
