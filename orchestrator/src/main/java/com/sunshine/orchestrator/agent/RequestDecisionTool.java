package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.processing.DecisionLabels;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ReAct 元工具 — 主 Agent 向用户出选择题并硬阻塞等待决策（独立于 HITL）。
 * AgentTool 形态以获取 toolUseId，避免多会话时 {@code sessions.size()!=1} 无法定位 messageId。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestDecisionTool implements AgentTool {

    public static final String NAME = "request_decision";

    private static final int MAX_PROMPT_CHARS = 500;
    private static final int MAX_LABEL_CHARS = 64;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentExecutionProperties executionProperties;
    private final DecisionRegistry decisionRegistry;
    private final DecisionTimelineSupport timelineSupport;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "向用户出选择题并等待作答。需求歧义或下一步依赖用户偏好时使用。勿用于写工具 HITL 确认。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("id", Map.of("type", "string", "description", "选项 id"));
        optionProps.put("label", Map.of("type", "string", "description", "选项展示文案"));
        Map<String, Object> optionSchema = Map.of(
                "type", "object",
                "properties", optionProps,
                "required", List.of("id", "label"));
        Map<String, Object> questionProps = new LinkedHashMap<>();
        questionProps.put("id", Map.of("type", "string", "description", "问题 id"));
        questionProps.put("prompt", Map.of("type", "string", "description", "题干"));
        questionProps.put("options", Map.of(
                "type", "array",
                "description", "选项≥2，仅 id+label",
                "items", optionSchema,
                "minItems", 2));
        questionProps.put("allowMultiple", Map.of(
                "type", "boolean",
                "description", "是否允许多选，默认 false"));
        Map<String, Object> questionSchema = Map.of(
                "type", "object",
                "properties", questionProps,
                "required", List.of("id", "prompt", "options"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "可选总标题"));
        props.put("questions", Map.of(
                "type", "array",
                "description", "问题数组≥1。项：{id, prompt, options:[{id,label}]≥2, allowMultiple?}",
                "items", questionSchema,
                "minItems", 1));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("questions"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                    Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
                    Object title = input.get("title");
                    Object questions = input.get("questions");
                    String text = requestDecision(
                            title != null ? String.valueOf(title) : null,
                            questions,
                            toolUseId);
                    return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
                })
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    /** 单测入口：无 toolUseId 时回退 activeMessageId（单会话）。 */
    String requestDecision(String title, Object questionsInput) {
        return requestDecision(title, questionsInput, null);
    }

    String requestDecision(String title, Object questionsInput, String toolUseId) {
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

        String messageId = StepEventBridge.resolveMessageIdForToolUse(toolUseId);
        if (!StringUtils.hasText(messageId)) {
            return errorJson("无法定位当前会话消息");
        }
        String mainBridge = StepEventBridge.activeMainBridge(messageId);
        if (!StringUtils.hasText(mainBridge)) {
            return errorJson("request_decision 仅可从主 Agent 调用");
        }
        String activeBridge = StepEventBridge.bridgeIdForToolUse(toolUseId);
        if (!StringUtils.hasText(activeBridge)) {
            activeBridge = StepEventBridge.activeBridgeId();
        }
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
            if ("skipped".equals(prior.outcome())) {
                return formatSkippedResult();
            }
            return formatSuccessResult(prior.title(), questions, prior.answers());
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
            if ("skipped".equals(result.outcome())) {
                return formatSkippedResult();
            }
            return formatSuccessResult(result.title(), questions, result.answers());
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

    /**
     * 成功短格式（D18）：outcome/title/choice — Resume 注入共用。
     * choice 为可读 label（经 questions 映射），禁止输出 questionId/optionId 内部标识。
     */
    public static String formatSuccessResult(
            String title, List<DecisionQuestion> questions, List<DecisionAnswer> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("outcome=answered");
        sb.append("\ntitle=").append(nullToEmpty(title));
        if (answers == null || answers.isEmpty()) {
            return sb.toString();
        }
        String choice = DecisionLabels.formatChoiceFromAnswers(questions, answers);
        if (StringUtils.hasText(choice)) {
            sb.append("\nchoice=").append(choice);
        }
        return sb.toString();
    }

    /**
     * 无题元数据兜底：仅输出 outcome/title 与用户手写内容，避免暴露内部 option id。
     */
    public static String formatSuccessResult(String title, List<DecisionAnswer> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("outcome=answered");
        sb.append("\ntitle=").append(nullToEmpty(title));
        if (answers == null) {
            return sb.toString();
        }
        for (DecisionAnswer answer : answers) {
            if (answer == null || answer.selectedOptionIds() == null
                    || !answer.selectedOptionIds().contains(DecisionOption.CUSTOM_ID)) {
                continue;
            }
            // 仅选中平台手写项时才输出 custom 行，避免脏 customInput 泄漏
            if (StringUtils.hasText(answer.customInput())) {
                sb.append("\ncustom=").append(answer.customInput().strip());
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

    /** 用户跳过问卷：Agent 应基于已有信息继续，勿立刻同参重调。 */
    public static String formatSkippedResult() {
        return "outcome=skipped";
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
