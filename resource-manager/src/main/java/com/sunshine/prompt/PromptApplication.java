package com.sunshine.prompt;

import com.sunshine.common.web.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.sunshine.prompt.repo")
@EntityScan(basePackages = "com.sunshine.prompt.entity")
@Import(GlobalExceptionHandler.class)
public class PromptApplication {
}
