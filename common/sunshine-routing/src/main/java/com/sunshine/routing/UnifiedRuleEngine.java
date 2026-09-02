package com.sunshine.routing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class UnifiedRuleEngine {

    public record Hit(String ruleId, RoutingPlanSpec plan, String reason) {}

    private final List<RoutingRuleDef> rules;

    public UnifiedRuleEngine(List<RoutingRuleDef> rules) {
        this.rules = rules.stream()
                .filter(RoutingRuleDef::enabled)
                .sorted(Comparator.comparingInt(RoutingRuleDef::priority).reversed()
                        .thenComparing(RoutingRuleDef::id))
                .toList();
    }

    public Optional<Hit> match(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return Optional.empty();
        }
        String query = userQuery.strip();
        for (RoutingRuleDef rule : rules) {
            Optional<String> reason = matchReason(query, rule);
            if (reason.isPresent()) {
                return Optional.of(new Hit(rule.id(), rule.plan(), reason.get()));
            }
        }
        return Optional.empty();
    }

    public static RoutingDryRunResult dryRun(String query, List<RoutingRuleDef> rules) {
        Optional<Hit> hit = new UnifiedRuleEngine(rules).match(query);
        if (hit.isPresent()) {
            return new RoutingDryRunResult(hit.get().ruleId(), false, "rule-engine");
        }
        return new RoutingDryRunResult(null, true, "would_llm");
    }

    private static Optional<String> matchReason(String query, RoutingRuleDef rule) {
        return switch (rule.matchType()) {
            case "structural" -> matchStructural(query, rule) ? Optional.of("structural:multi-step-plan") : Optional.empty();
            case "regex" -> matchRegex(query, rule) ? Optional.of("regex:" + rule.id()) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static boolean matchStructural(String query, RoutingRuleDef rule) {
        if (!matchesAnyPattern(query, rule.patterns())) {
            return false;
        }
        return domainGroupHitCount(query, rule.domainGroups()) >= Math.max(1, rule.minDomainGroups());
    }

    private static boolean matchRegex(String query, RoutingRuleDef rule) {
        List<String> patterns = rule.patterns();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        boolean all = "all".equalsIgnoreCase(rule.match());
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                if (all) {
                    return false;
                }
                continue;
            }
            boolean hit = Pattern.compile(raw).matcher(query).find();
            if (all && !hit) {
                return false;
            }
            if (!all && hit) {
                return true;
            }
        }
        return all;
    }

    private static boolean matchesAnyPattern(String query, List<String> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return false;
        }
        for (String raw : rawPatterns) {
            if (raw == null || raw.isBlank()) {
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
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
