package com.sunshine.prompt.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.prompt.dto.RoutingDryRunRequest;
import com.sunshine.prompt.dto.RoutingDryRunResponse;
import com.sunshine.prompt.dto.RoutingRuleInput;
import com.sunshine.prompt.dto.RoutingValidateRequest;
import com.sunshine.prompt.dto.RoutingValidateResponse;
import com.sunshine.prompt.dto.RoutingWarningItem;
import com.sunshine.prompt.exception.PromptErrorCode;
import com.sunshine.routing.RoutingConflictDetector;
import com.sunshine.routing.RoutingRuleDef;
import com.sunshine.routing.UnifiedRuleEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PromptRoutingService {
    private final PromptRoutingSupport routingSupport;

    public RoutingValidateResponse validate(RoutingValidateRequest request) {
        List<RoutingRuleDef> rules = resolveRules(request != null ? request.rules() : null);
        List<RoutingWarningItem> warnings = RoutingConflictDetector.detect(rules).stream()
                .map(w -> new RoutingWarningItem(w.message()))
                .toList();
        return new RoutingValidateResponse(warnings);
    }

    public RoutingDryRunResponse dryRun(RoutingDryRunRequest request) {
        if (request == null || !StringUtils.hasText(request.query())) {
            throw new BizException(CommonErrorCode.BAD_REQUEST);
        }
        List<RoutingRuleDef> rules = routingSupport.loadEnabledRules();
        String mode = StringUtils.hasText(request.mode()) ? normalizeMode(request.mode()) : "fast";
        Optional<UnifiedRuleEngine.Hit> hit = matchForMode(rules, request.query(), mode);
        if (hit.isPresent()) {
            return new RoutingDryRunResponse(hit.get().ruleId(), "rule", hit.get().plan());
        }
        return new RoutingDryRunResponse(null, "l3", null);
    }

    /** v6 同轨匹配：先按锁定模式过滤规则再跑引擎，避免错误轨高优规则抢占 */
    private static Optional<UnifiedRuleEngine.Hit> matchForMode(
            List<RoutingRuleDef> rules, String query, String mode) {
        List<RoutingRuleDef> filtered = rules.stream()
                .filter(r -> isRuleCompatible(r, mode))
                .toList();
        return new UnifiedRuleEngine(filtered).match(query);
    }

    /** 轨 A（fast/pro 共用 mode=fast 规则）；轨 B 仅 mode=workflow。 */
    private static boolean isRuleCompatible(RoutingRuleDef rule, String mode) {
        String ruleMode = rule.plan() != null ? rule.plan().mode() : null;
        String normalized = normalizeMode(ruleMode);
        if ("workflow".equals(mode)) {
            return "workflow".equals(normalized);
        }
        return "fast".equals(normalized);
    }

    /** v6 规则 mode 直值 fast / pro / workflow；空或未知按 workflow 处理 */
    private static String normalizeMode(String raw) {
        if (raw == null) {
            return "workflow";
        }
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "fast" -> "fast";
            case "pro" -> "pro";
            case "workflow" -> "workflow";
            default -> "workflow";
        };
    }

    private List<RoutingRuleDef> resolveRules(List<RoutingRuleInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return routingSupport.loadEnabledRules();
        }
        return inputs.stream().map(this::toDef).toList();
    }

    private RoutingRuleDef toDef(RoutingRuleInput input) {
        if (input == null || !StringUtils.hasText(input.id()) || !StringUtils.hasText(input.contentJson())) {
            throw new BizException(PromptErrorCode.ROUTING_RULE_INPUT_REQUIRED);
        }
        int priority = input.priority() != null ? input.priority() : 0;
        boolean enabled = input.enabled() == null || input.enabled();
        return routingSupport.parse(input.id(), priority, enabled, input.contentJson());
    }
}
