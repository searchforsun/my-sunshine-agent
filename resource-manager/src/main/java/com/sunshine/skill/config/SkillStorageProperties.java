package com.sunshine.skill.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "skill")
public class SkillStorageProperties {

    private Storage storage = new Storage();

    @Getter
    @Setter
    public static class Storage {
        /** local | minio */
        private String type = "minio";
        private String baseDir = "data/skills";
        private Minio minio = new Minio();
    }

    @Getter
    @Setter
    public static class Minio {
        private String endpoint = "http://ecs4c16g:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin123";
        private String bucket = "sunshine-skills";
    }

    public String getBaseDir() {
        return storage.getBaseDir();
    }

    public boolean isMinio() {
        return "minio".equalsIgnoreCase(storage.getType());
    }
}
