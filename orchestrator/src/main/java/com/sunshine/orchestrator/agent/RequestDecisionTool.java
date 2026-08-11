package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.DecisionLabels;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ReAct 元工具 — 主 Agent 向用户出选择题并硬阻塞等待决策（独立于 HITL） */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestDecisionTool {

    public static final String NAME = "request_decision";

    private static final int MAX_QUESTION_CHARS = 500;
    private static final int MAX_LABEL_CHARS = 64;
    private static final int MAX_DESCRIPTION_CHARS = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentExecutionProperties executionProperties;
    private final DecisionRegistry decisionRegistry;
    private final DecisionTimelineSupport timelineSupport;

    @Tool(name = NAME, description = "需求歧义或多方案抉择时向用户出选择题并等待决策；勿用于写工具确认。")
    public String requestDecision(
            @ToolParam(name = "question", description = "决策问题（中文）") String question,
            @ToolParam(name = "options", description = "JSON 数组：[{value,label,description?,requireInput?}]，≥2")
                    String optionsJson,
            @ToolParam(name = "allow_custom_input", description = "是否允许自定义输入，默认 false")
                    Boolean allowCustomInput) {
        AgentExecutionProperties.React.Decision cfg = decisionConfig();
        if (cfg == null || !cfg.isEnabled()) {
            return errorJson("request_decision 未启用");
        }
        if (!StringUtils.hasText(question)) {
            return errorJson("question 不能为空");
        }
        String questionText = question.strip();
        if (questionText.length() > MAX_QUESTION_CHARS) {
            return errorJson("question 不能超过 " + MAX_QUESTION_CHARS + " 字");
        }
        List<DecisionOption> options;
        try {
            options = parseAndValidateOptions(optionsJson);
        } catch (IllegalArgumentException e) {
            return errorJson(e.getMessage());
        }
        boolean allowCustom = Boolean.TRUE.equals(allowCustomInput);

        String messageId = StepEventBridge.activeMessageId();
        if (!StringUtils.hasText(messageId)) {
            return errorJson("无法定位当前会话消息");
        }
        String mainBridge = StepEventBridge.activeMainBridge(messageId);
        if (!StringUtils.hasText(mainBridge)) {
            return errorJson("request_decision 仅可从主 Agent 调用");
        }
        String activeBridge = StepEventBridge.activeBridgeId();
        if (StringUtils.hasText(activeBridge) && activeBridge.startsWith("sub-")) {
            return errorJson("子 Agent 不可调用 request_decision");
        }
        if (decisionRegistry.hasAwaiting(messageId)) {
            return errorJson("当前消息已有待决策，勿重复调用 request_decision");
        }

        String fingerprint = DecisionFingerprint.of(questionText, options);
        var preApproved = StepEventBridge.consumeDecisionPreApproval(messageId, fingerprint);
        if (preApproved.isPresent()) {
            DecisionResult prior = preApproved.get();
            String labelForChoice = resolveLabel(options, prior.choice());
            return formatSuccessResult(prior.choice(), labelForChoice, prior.customInput());
        }

        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext(messageId);
        String userId = audit != null && StringUtils.hasText(audit.userId()) ? audit.userId() : "";

        DecisionRegistry.Registration reg;
        try {
            reg = decisionRegistry.register(messageId, userId, questionText, options, allowCustom);
        } catch (IllegalStateException e) {
            return errorJson("当前消息已有待决策，勿重复调用 request_decision");
        }

        timelineSupport.begin(
                mainBridge, reg.token(), questionText, options, allowCustom, reg.expiresAt());
        try {
            DecisionResult result = decisionRegistry.awaitDecision(reg);
            if ("__timeout__".equals(result.choice())) {
                timelineSupport.pause(mainBridge, reg.token(), DecisionLabels.afterTimeout());
                return formatTimeoutResult(decisionRegistry.timeoutSec());
            }
            if ("__cancelled__".equals(result.choice())) {
                timelineSupport.pause(mainBridge, reg.token(), DecisionLabels.afterCancel());
                return formatCancelledResult();
            }
            String labelForChoice = resolveLabel(options, result.choice());
            timelineSupport.complete(mainBridge, reg.token(), result, labelForChoice);
            return formatSuccessResult(result.choice(), labelForChoice, result.customInput());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timelineSupport.pause(mainBridge, reg.token(), DecisionLabels.afterCancel());
            return formatCancelledResult();
        } catch (RuntimeException e) {
            String msg = StringUtils.hasText(e.getMessage()) ? e.getMessage().strip() : "决策失败";
            log.warn("[RequestDecisionTool] await 失败: {}", msg);
            timelineSupport.fail(mainBridge, reg.token(), msg);
            return errorJson(msg);
        }
    }

    private AgentExecutionProperties.React.Decision decisionConfig() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null ? react.getDecision() : null;
    }

    private static List<DecisionOption> parseAndValidateOptions(String optionsJson) {
        if (!StringUtils.hasText(optionsJson)) {
            throw new IllegalArgumentException("options 不能为空，至少 2 项");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(optionsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("options JSON 解析失败");
        }
        if (root == null || !root.isArray() || root.size() < 2) {
            throw new IllegalArgumentException("options 至少需要 2 项");
        }
        List<DecisionOption> options = new ArrayList<>(root.size());
        Set<String> values = new HashSet<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("options 每项须为对象");
            }
            String value = textField(node, "value");
            String label = textField(node, "label");
            if (!StringUtils.hasText(value) || !StringUtils.hasText(label)) {
                throw new IllegalArgumentException("options 每项 value/label 不能为空");
            }
            String valueText = value.strip();
            String labelText = label.strip();
            if (labelText.length() > MAX_LABEL_CHARS) {
                throw new IllegalArgumentException("options label 不能超过 " + MAX_LABEL_CHARS + " 字");
            }
            String description = textField(node, "description");
            String descriptionText = StringUtils.hasText(description) ? description.strip() : null;
            if (descriptionText != null && descriptionText.length() > MAX_DESCRIPTION_CHARS) {
                throw new IllegalArgumentException(
                        "options description 不能超过 " + MAX_DESCRIPTION_CHARS + " 字");
            }
            if (!values.add(valueText)) {
                throw new IllegalArgumentException("options value 不能重复");
            }
            boolean requireInput = node.path("requireInput").asBoolean(false);
            options.add(new DecisionOption(valueText, labelText, descriptionText, requireInput));
        }
        return List.copyOf(options);
    }

    private static String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText(null);
    }

    private static String resolveLabel(List<DecisionOption> options, String choice) {
        if (!StringUtils.hasText(choice)) {
            return "";
        }
        return options.stream()
                .filter(o -> choice.equals(o.value()))
                .map(DecisionOption::label)
                .findFirst()
                .orElse("");
    }

    public static String formatSuccessResult(String choice, String label, String customInput) {
        String input = customInput != null ? customInput : "";
        return "choice=" + nullToEmpty(choice)
                + "\nlabel=" + nullToEmpty(label)
                + "\ncustomInput=" + input;
    }

    static String formatTimeoutResult(int timeoutSec) {
        return "choice=__timeout__\ntimeoutSec=" + timeoutSec;
    }

    static String formatCancelledResult() {
        return "choice=__cancelled__";
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String nullToEmpty(String text) {
        return text != null ? text : "";
    }
}
