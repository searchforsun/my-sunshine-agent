package com.sunshine.skill;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.skill.config.SkillStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties(SkillStorageProperties.class)
@Import(GlobalExceptionHandler.class)
public class SkillManagerApplication {
}
