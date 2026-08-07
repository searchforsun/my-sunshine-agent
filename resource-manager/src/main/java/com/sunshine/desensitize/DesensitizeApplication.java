package com.sunshine.desensitize;

import com.sunshine.desensitize.config.DesensitizeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableDiscoveryClient
@EnableConfigurationProperties(DesensitizeProperties.class)
public class DesensitizeApplication {
}
