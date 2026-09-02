package com.sunshine.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoutingConflictDetector {

    public record Warning(String message) {}

    private RoutingConflictDetector() {}

    public static List<Warning> detect(List<RoutingRuleDef> rules) {
        List<Warning> warnings = new ArrayList<>();
        detectSamePriority(warnings, rules);
        detectRegexPatternContainment(warnings, rules);
        return warnings;
    }

    private static void detectSamePriority(List<Warning> warnings, List<RoutingRuleDef> rules) {
        Map<Integer, List<String>> byPriority = new HashMap<>();
        for (RoutingRuleDef rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            byPriority.computeIfAbsent(rule.priority(), k -> new ArrayList<>()).add(rule.id());
        }
        for (Map.Entry<Integer, List<String>> entry : byPriority.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add(new Warning("same priority " + entry.getKey() + ": " + String.join(", ", entry.getValue())));
            }
        }
    }

    private static void detectRegexPatternContainment(List<Warning> warnings, List<RoutingRuleDef> rules) {
        List<RoutingRuleDef> regexRules = rules.stream()
                .filter(RoutingRuleDef::enabled)
                .filter(r -> "regex".equals(r.matchType()))
                .toList();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < regexRules.size(); i++) {
            RoutingRuleDef a = regexRules.get(i);
            List<String> patternsA = a.patterns() != null ? a.patterns() : List.of();
            for (int j = i + 1; j < regexRules.size(); j++) {
                RoutingRuleDef b = regexRules.get(j);
                List<String> patternsB = b.patterns() != null ? b.patterns() : List.of();
                for (String pa : patternsA) {
                    if (pa == null || pa.isBlank()) {
                        continue;
                    }
                    for (String pb : patternsB) {
                        if (pb == null || pb.isBlank()) {
                            continue;
                        }
                        if (pa.contains(pb) || pb.contains(pa)) {
                            String key = containmentKey(a.id(), b.id(), pa, pb);
                            if (seen.add(key)) {
                                warnings.add(new Warning(
                                        "regex pattern containment between " + a.id() + " and " + b.id()
                                                + " (" + pa + " / " + pb + ")"));
                            }
                        }
                    }
                }
            }
        }
    }

    private static String containmentKey(String idA, String idB, String pa, String pb) {
        if (idA.compareTo(idB) <= 0) {
            return idA + "|" + idB + "|" + pa + "|" + pb;
        }
        return idB + "|" + idA + "|" + pb + "|" + pa;
    }
}
