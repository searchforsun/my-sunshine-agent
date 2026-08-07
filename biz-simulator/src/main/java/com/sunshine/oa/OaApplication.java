package com.sunshine.oa;

import com.sunshine.common.web.GlobalExceptionHandler;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableDiscoveryClient
@Import(GlobalExceptionHandler.class)
public class OaApplication {
}
