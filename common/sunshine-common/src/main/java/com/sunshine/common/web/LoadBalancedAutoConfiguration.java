package com.sunshine.common.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 自动注入 @LoadBalanced WebClient.Builder，供所有模块的服务间调用走 Nacos 服务发现。
 * 仅当 classpath 存在 WebClient（即模块引入了 spring-boot-starter-webflux）时生效。
 */
@Configuration
@ConditionalOnClass(WebClient.class)
public class LoadBalancedAutoConfiguration {

    @Bean
    @Primary
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
