package com.sunshine.tools.sdk.autoconfigure;

import com.sunshine.tools.sdk.config.SunshineToolProperties;
import com.sunshine.tools.sdk.registry.SunshineToolRegistry;
import com.sunshine.tools.sdk.web.SunshineToolController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties(SunshineToolProperties.class)
@ConditionalOnProperty(name = "sunshine.tools.enabled", havingValue = "true", matchIfMissing = true)
public class SunshineToolAutoConfiguration {

    @Bean
    SunshineToolRegistry sunshineToolRegistry(ApplicationContext ctx,
                                              SunshineToolProperties props,
                                              Environment env) {
        return new SunshineToolRegistry(ctx, props, env);
    }

    @Bean
    SunshineToolController sunshineToolController(SunshineToolRegistry registry) {
        return new SunshineToolController(registry);
    }
}
