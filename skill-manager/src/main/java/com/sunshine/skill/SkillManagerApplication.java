package com.sunshine.skill;

import com.sunshine.skill.config.SkillStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SkillStorageProperties.class)
public class SkillManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillManagerApplication.class, args);
    }
}
