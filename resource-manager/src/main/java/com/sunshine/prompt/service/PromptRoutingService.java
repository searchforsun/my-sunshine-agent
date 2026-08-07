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
        Optional<UnifiedRuleEngine.Hit> hit = new UnifiedRuleEngine(rules).match(request.query());
        if (hit.isPresent()) {
            return new RoutingDryRunResponse(hit.get().ruleId(), false, "rule-engine", hit.get().plan());
        }
        return new RoutingDryRunResponse(null, true, "would_llm", null);
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
