package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Nacos agent.skill — @ 绑定与强提示句式 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.skill")
public class SkillBindingProperties {

    /** P1 强提示正则，须含命名组 {@code skill} */
    private List<String> hintPatterns = defaultHintPatterns();

    private static List<String> defaultHintPatterns() {
        List<String> patterns = new ArrayList<>();
        patterns.add("请使用\\s+(?<skill>[\\w\\u4e00-\\u9fff-]+)\\s+skill");
        patterns.add("用\\s+(?<skill>[\\w\\u4e00-\\u9fff-]+)\\s+skill\\s+处理");
        patterns.add("请用\\s+(?<skill>[\\w\\u4e00-\\u9fff-]+)\\s+skill");
        return patterns;
    }
}
