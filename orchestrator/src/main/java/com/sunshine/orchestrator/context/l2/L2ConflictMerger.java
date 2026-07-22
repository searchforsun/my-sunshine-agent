package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * L2 同 key 冲突：时间优先；constraint/fact 需达到更高覆盖置信门槛。
 */
@Component
public class L2ConflictMerger {

    private static final Set<String> ELEVATED_KINDS = Set.of("constraint", "fact");

    public enum Decision {
        ACCEPT,
        REJECT
    }

    public record Candidate(String kind, String key, String value, double confidence) {
    }

    public Decision decide(UserContextStateEntity existingActive, Candidate incoming, ContextProperties.L2 props) {
        if (incoming == null || !StringUtils.hasText(incoming.kind()) || !StringUtils.hasText(incoming.key())) {
            return Decision.REJECT;
        }
        if (existingActive == null) {
            return Decision.ACCEPT;
        }
        ContextProperties.L2 l2 = props != null ? props : new ContextProperties.L2();
        String kind = normalizeKind(incoming.kind());
        if (ELEVATED_KINDS.contains(kind)) {
            double bar = Math.max(l2.getConstraintOverwriteConfidence(), existingActive.getConfidence());
            return incoming.confidence() >= bar ? Decision.ACCEPT : Decision.REJECT;
        }
        // preference / goal / decision / agreement / profile：新置信不低于旧即可覆盖（时间优先）
        return incoming.confidence() >= existingActive.getConfidence()
                ? Decision.ACCEPT
                : Decision.REJECT;
    }

    static String normalizeKind(String kind) {
        return kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
    }

    static boolean isElevatedKind(String kind) {
        return ELEVATED_KINDS.contains(normalizeKind(kind));
    }
}
