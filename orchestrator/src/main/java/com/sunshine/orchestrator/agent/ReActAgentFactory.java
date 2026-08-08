package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 每次对话创建独立 ReActAgent，避免单例残留 pending tool call / 并发冲突。
 * 2.0 起 `.memory()` 被 `.stateStore(AgentStateStore)` 取代；
 * AutoContextMemory/AutoContextHook/AutoContextConfig 在 2.0 已整体移除，压缩改在 P2 用原生
 * CompactionConfig 重做（阈值字段保留于 {@code MemoryProperties.AutoContext} 供 P2 对标）。
 * enablePendingToolRecovery=true：续跑时 stateStore 恢复的 AgentState 可能含 pending tool calls，
 * AgentScope 2.0 会自动生成占位 tool result 让 agent 继续推理（而非报错卡死）。
 * base system-prompt 读 {@link PromptCatalogHolder}（id={@code system-prompt}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgentFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PromptCatalogHolder catalogHolder;
    private final AgentExecutionProperties executionProperties;
    private final DynamicToolkitFactory dynamicToolkitFactory;
    private final ProcessingStepMiddlewareFactory middlewareFactory;
    private final AgentStateStore stateStore;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://sunshine-llm-gateway/v1}")
    private String modelBaseUrl;
    @Value("${agent.model.max-tokens:16384}")
    private int maxTokens;
    @Value("${agent.model.api-key:}")
    private String apiKey;
    /**
     * 模型上下文窗口（token）。供 AgentScope CompactionConfig 动态触发使用
     * （effectiveTrigger = contextWindow - reserved）；须与实际模型窗口一致，
     * 否则 run 内压缩触发点会偏离真实溢出点。
     */
    @Value("${agent.model.context-window:256000}")
    private int contextWindowSize;

    public ReActAgent create(AgentRunRequest request) {
        Toolkit toolkit = resolveToolkit(request);
        int maxIters = resolveMaxIters(request);
        OpenAIChatModel model = buildModel(request);
        log.info("[ReActAgentFactory] role={} skill={} tools={} maxIters={}",
                request.role(), request.skillId(), toolkit.getToolNames(), maxIters);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(resolveAgentName(request))
                .sysPrompt(composeSystemPrompt(request))
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .stateStore(stateStore)
                .enablePendingToolRecovery(true)
                .middleware(middlewareFactory.shared());
        // 原生 TaskList：todo_write + TaskReminderMiddleware，任务列表随 AgentState checkpoint 持久化，
        // 中断恢复后（含 id）随 stateStore 还原。仅主 Agent 开任务板（SUB/专家无独立任务清单）。
        if (request.role() == AgentRole.MAIN && isTaskBoardEnabled()) {
            builder.enableTaskList(true);
        }
        return builder.build();
    }

    private boolean isTaskBoardEnabled() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled();
    }

    private OpenAIChatModel buildModel(AgentRunRequest request) {
        String overriddenModel = modelName;
        String overriddenBaseUrl = modelBaseUrl;
        int resolvedMaxTokens = maxTokens;
        if (request != null && request.modelConfigJson() != null && !request.modelConfigJson().isBlank()
                && !"{}".equals(request.modelConfigJson())) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = MAPPER.readValue(request.modelConfigJson(), Map.class);
                if (config.get("model") instanceof String m && !m.isBlank()) {
                    overriddenModel = m;
                }
                if (config.get("baseUrl") instanceof String b && !b.isBlank()) {
                    overriddenBaseUrl = b;
                }
                if (config.get("maxTokens") instanceof Number n) {
                    resolvedMaxTokens = n.intValue();
                }
            } catch (Exception e) {
                log.warn("[ReActAgentFactory] modelConfigJson 解析失败: {}", e.getMessage());
            }
        }
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(overriddenModel)
                .baseUrl(overriddenBaseUrl)
                .contextWindowSize(contextWindowSize)
                .generateOptions(GenerateOptions.builder().maxTokens(resolvedMaxTokens).build())
                .stream(true)
                .build();
    }

    public String composeSystemPrompt(AgentRunRequest request) {
        String base = catalogHolder.snapshot().entry("system-prompt")
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[ReActAgentFactory] catalog missing id=system-prompt");
                    return "";
                });
        String overlay = request.systemOverlay();
        if (!StringUtils.hasText(overlay)) {
            return base;
        }
        String trimmed = overlay.strip();
        if (base.isBlank()) {
            return trimmed;
        }
        return base + "\n\n" + trimmed;
    }

    Toolkit resolveToolkit(AgentRunRequest request) {
        if (request.role() == AgentRole.SUB) {
            return dynamicToolkitFactory.buildForSubAgent(
                    request.toolWhitelist(), request.tenantId(), request.skillId(), request.userId());
        }
        return dynamicToolkitFactory.build(request.tenantId(), request.skillId(), request.userId());
    }

    public int resolveMaxIters(AgentRunRequest request) {
        return request.maxIters() > 0 ? request.maxIters() : executionProperties.getReact().getMaxIters();
    }

    private static String resolveAgentName(AgentRunRequest request) {
        if (request.role() == AgentRole.SUB) {
            return "Sunshine-SubAgent";
        }
        return "Sunshine-Assistant";
    }
}
