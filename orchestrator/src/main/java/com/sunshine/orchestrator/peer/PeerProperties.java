package com.sunshine.orchestrator.peer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Nacos agent.peer — 协作模板与轮次上限 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.peer")
public class PeerProperties {

    private int maxRounds = 3;
    private Map<String, PeerTemplateSpec> templates = defaultTemplates();

    private static Map<String, PeerTemplateSpec> defaultTemplates() {
        Map<String, PeerTemplateSpec> map = new LinkedHashMap<>();
        PeerTemplateSpec tpl = new PeerTemplateSpec();
        tpl.setDisplayName("合规交叉审查");
        PeerRoleSpec policy = new PeerRoleSpec();
        policy.setSkillId("policy-review");
        policy.setDisplayName("制度专家");
        PeerRoleSpec finance = new PeerRoleSpec();
        finance.setSkillId("finance-analysis");
        finance.setDisplayName("财务专家");
        PeerRoleSpec moderator = new PeerRoleSpec();
        moderator.setSkillId("compliance-check");
        moderator.setDisplayName("仲裁汇总");
        moderator.setModerator(true);
        tpl.setRoles(java.util.List.of(policy, finance, moderator));
        map.put("compliance-cross-review", tpl);
        return map;
    }

    @Data
    public static class PeerTemplateSpec {
        private String displayName;
        private java.util.List<PeerRoleSpec> roles = java.util.List.of();
    }

    @Data
    public static class PeerRoleSpec {
        private String skillId;
        private String displayName;
        private String systemOverlay;
        /** 末位角色默认仲裁 */
        private boolean moderator;
    }
}
