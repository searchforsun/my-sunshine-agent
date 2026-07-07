package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.RoutingRuleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/** L1 peer 句式 — 对等协商 / 交叉验证（配置见 agent.routing.peer） */
@Component
@RefreshScope
@RequiredArgsConstructor
public class PeerPatternMatcher {

    private final RoutingRuleProperties properties;

    public boolean looksLikePeerCollab(String userQuery) {
        RoutingRuleProperties.Peer cfg = properties.getPeer();
        if (cfg == null || !cfg.isEnabled() || !StringUtils.hasText(userQuery)) {
            return false;
        }
        return matchesAnyPattern(userQuery.strip(), cfg.getStructuralPatterns());
    }

    public String defaultTemplateId() {
        RoutingRuleProperties.Peer cfg = properties.getPeer();
        if (cfg != null && StringUtils.hasText(cfg.getDefaultTemplateId())) {
            return cfg.getDefaultTemplateId().strip();
        }
        return "compliance-cross-review";
    }

    private static boolean matchesAnyPattern(String query, List<String> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return false;
        }
        for (String raw : rawPatterns) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            if (Pattern.compile(raw).matcher(query).find()) {
                return true;
            }
        }
        return false;
    }
}
