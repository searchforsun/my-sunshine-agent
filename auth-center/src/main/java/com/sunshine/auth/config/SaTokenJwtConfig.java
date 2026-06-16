package com.sunshine.auth.config;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SaTokenJwtConfig {

    @Bean
    public StpLogic stpLogicJwt() {
        return new StpLogicJwtForSimple();
    }
}
