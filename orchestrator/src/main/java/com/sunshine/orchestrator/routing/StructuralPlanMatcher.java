package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.RoutingRuleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** L1/L2 共用 — 多步句式 + 跨领域信号（配置见 Nacos agent.routing.structural） */
@Component
@RefreshScope
@RequiredArgsConstructor
public class StructuralPlanMatcher {

    private final RoutingRuleProperties properties;

    public boolean looksLikeMultiStepPlan(String userQuery) {
        RoutingRuleProperties.Structural cfg = properties.getStructural();
        if (cfg == null || !cfg.isEnabled() || !StringUtils.hasText(userQuery)) {
            return false;
        }
        String q = userQuery.strip();
        if (!matchesAnyPattern(q, cfg.getMultiStepPatterns())) {
            return false;
        }
        return domainGroupHitCount(q, cfg.getDomainGroups()) >= Math.max(1, cfg.getMinDomainGroups());
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

    private static int domainGroupHitCount(String query, Map<String, List<String>> domainGroups) {
        if (domainGroups == null || domainGroups.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (List<String> keywords : domainGroups.values()) {
            if (keywords == null || keywords.isEmpty()) {
                continue;
            }
            if (containsAny(query, keywords)) {
                n++;
            }
        }
        return n;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (StringUtils.hasText(kw) && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
