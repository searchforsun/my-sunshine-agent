package com.sunshine.skill;

import com.sunshine.common.web.GlobalExceptionHandler;
import com.sunshine.skill.config.SkillStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(SkillStorageProperties.class)
@Import(GlobalExceptionHandler.class)
public class SkillManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillManagerApplication.class, args);
    }
}
