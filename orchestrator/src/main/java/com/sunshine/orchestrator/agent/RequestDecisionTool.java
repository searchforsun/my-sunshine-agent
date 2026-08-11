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

    private static final int MAX_PROMPT_CHARS = 500;
    private static final int MAX_LABEL_CHARS = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentExecutionProperties executionProperties;
    private final DecisionRegistry decisionRegistry;
    private final DecisionTimelineSupport timelineSupport;

    /**
     * 工具注解对齐 Cursor ask_question：description 只写短「何时用」；
     * 字段契约写在 @ToolParam；禁止正文出题等策略放 Catalog overlay。
     */
    @Tool(name = NAME,
            description = "向用户出选择题并等待作答。需求歧义或下一步依赖用户偏好时使用。勿用于写工具 HITL 确认。")
    public String requestDecision(
            @ToolParam(name = "title", description = "可选总标题") String title,
            @ToolParam(name = "questions",
                    description = "问题数组≥1。项：{id, prompt, options:[{id,label}]≥2, allowMultiple?}")
                    Object questionsInput) {
        AgentExecutionProperties.React.Decision cfg = decisionConfig();
        if (cfg == null || !cfg.isEnabled()) {
            return errorJson("request_decision 未启用");
        }
        String titleText = title != null ? title.strip() : "";
        List<DecisionQuestion> questions;
        try {
            questions = parseAndValidateQuestions(questionsInput);
        } catch (IllegalArgumentException e) {
            return errorJson(e.getMessage());
        }

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

        String fingerprint = DecisionFingerprint.of(titleText, questions);
        var preApproved = StepEventBridge.consumeDecisionPreApproval(messageId, fingerprint);
        if (preApproved.isPresent()) {
            DecisionResult prior = preApproved.get();
            return formatSuccessResult(prior.title(), prior.answers());
        }

        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext(messageId);
        String userId = audit != null && StringUtils.hasText(audit.userId()) ? audit.userId() : "";

        DecisionRegistry.Registration reg;
        try {
            reg = decisionRegistry.register(messageId, userId, titleText, questions);
        } catch (IllegalStateException e) {
            return errorJson("当前消息已有待决策，勿重复调用 request_decision");
        }

        timelineSupport.begin(mainBridge, reg.token(), titleText, questions, reg.expiresAt());
        try {
            DecisionResult result = decisionRegistry.awaitDecision(reg);
            if ("timeout".equals(result.outcome())) {
                timelineSupport.pause(mainBridge, reg.token(), DecisionLabels.afterTimeout());
                return formatTimeoutResult(decisionRegistry.timeoutSec());
            }
            if ("cancelled".equals(result.outcome())) {
                timelineSupport.pause(mainBridge, reg.token(), DecisionLabels.afterCancel());
                return formatCancelledResult();
            }
            timelineSupport.complete(mainBridge, reg.token(), result);
            return formatSuccessResult(result.title(), result.answers());
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

    /** 兼容模型传 JSON 字符串或原生数组（AgentScope 常见两种形态）；拒绝旧扁平 options 契约。 */
    static List<DecisionQuestion> parseAndValidateQuestions(Object questionsInput) {
        if (questionsInput == null) {
            throw new IllegalArgumentException("questions 不能为空，至少 1 项");
        }
        JsonNode root;
        try {
            if (questionsInput instanceof String text) {
                if (!StringUtils.hasText(text)) {
                    throw new IllegalArgumentException("questions 不能为空，至少 1 项");
                }
                root = MAPPER.readTree(text.strip());
            } else if (questionsInput instanceof JsonNode node) {
                root = node;
            } else {
                root = MAPPER.valueToTree(questionsInput);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("questions 解析失败：须为数组（或数组的 JSON 字符串）");
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("questions 至少需要 1 项（JSON 数组）");
        }
        List<DecisionQuestion> questions = new ArrayList<>(root.size());
        Set<String> questionIds = new HashSet<>();
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("questions 每项须为对象");
            }
            String id = textField(node, "id");
            String prompt = textField(node, "prompt");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(prompt)) {
                throw new IllegalArgumentException("questions 每项 id/prompt 不能为空");
            }
            String idText = id.strip();
            String promptText = prompt.strip();
            if (promptText.length() > MAX_PROMPT_CHARS) {
                throw new IllegalArgumentException("questions prompt 不能超过 " + MAX_PROMPT_CHARS + " 字");
            }
            if (!questionIds.add(idText)) {
                throw new IllegalArgumentException("questions id 不能重复");
            }
            List<DecisionOption> options = parseAndValidateQuestionOptions(node.get("options"));
            boolean allowMultiple = node.path("allowMultiple").asBoolean(false);
            questions.add(new DecisionQuestion(idText, promptText, options, allowMultiple));
        }
        return List.copyOf(questions);
    }

    private static List<DecisionOption> parseAndValidateQuestionOptions(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() < 2) {
            throw new IllegalArgumentException("options 至少需要 2 项（JSON 数组）");
        }
        List<DecisionOption> options = new ArrayList<>(optionsNode.size());
        Set<String> optionIds = new HashSet<>();
        for (JsonNode node : optionsNode) {
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("options 每项须为对象");
            }
            String id = textField(node, "id");
            String label = textField(node, "label");
            if (!StringUtils.hasText(id) || !StringUtils.hasText(label)) {
                throw new IllegalArgumentException("options 每项 id/label 不能为空");
            }
            String idText = id.strip();
            String labelText = label.strip();
            // 平台保留：手写项由 UI 注入，禁止模型 options 占用
            if (DecisionOption.CUSTOM_ID.equals(idText)) {
                throw new IllegalArgumentException("options id 不可使用保留值 " + DecisionOption.CUSTOM_ID);
            }
            if (labelText.length() > MAX_LABEL_CHARS) {
                throw new IllegalArgumentException("options label 不能超过 " + MAX_LABEL_CHARS + " 字");
            }
            if (!optionIds.add(idText)) {
                throw new IllegalArgumentException("options id 不能重复");
            }
            options.add(new DecisionOption(idText, labelText));
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

    /** 成功短格式（D18）：outcome/title/q.* — Resume 注入共用。 */
    public static String formatSuccessResult(String title, List<DecisionAnswer> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("outcome=answered");
        sb.append("\ntitle=").append(nullToEmpty(title));
        if (answers == null) {
            return sb.toString();
        }
        for (DecisionAnswer answer : answers) {
            if (answer == null || !StringUtils.hasText(answer.questionId())) {
                continue;
            }
            String ids = answer.selectedOptionIds() == null
                    ? ""
                    : String.join(",", answer.selectedOptionIds());
            sb.append("\nq.").append(answer.questionId().strip()).append('=').append(ids);
            // 仅选中平台手写项时才输出 custom 行，避免脏 customInput 泄漏
            if (answer.selectedOptionIds() != null
                    && answer.selectedOptionIds().contains(DecisionOption.CUSTOM_ID)
                    && StringUtils.hasText(answer.customInput())) {
                sb.append("\nq.").append(answer.questionId().strip())
                        .append(".custom=").append(answer.customInput());
            }
        }
        return sb.toString();
    }

    static String formatTimeoutResult(int timeoutSec) {
        return "outcome=timeout\ntimeoutSec=" + timeoutSec;
    }

    static String formatCancelledResult() {
        return "outcome=cancelled";
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
