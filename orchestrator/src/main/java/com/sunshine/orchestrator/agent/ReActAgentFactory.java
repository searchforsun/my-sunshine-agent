package com.sunshine.orchestrator.agent;

import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.transport.LoadBalancedWebClientTransport;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.plan.harness.WorkerDispatchTool;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.registry.ResolvedModelScene;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * 每次对话创建独立 ReActAgent，避免单例残留 pending tool call / 并发冲突。
 * 模型名 / 窗口来自 {@link ModelSceneResolver}（D10），不再读 Nacos agent.model.name。
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
    /** @LoadBalanced WebClient.Builder 由 sunshine-common 自动注入，走 Nacos 服务发现 */
    private final WebClient.Builder webClientBuilder;
    private final ModelSceneResolver modelSceneResolver;
    /** 惰性注入，避免 Factory → DispatchTool → AgentRuntime → Factory 环 */
    private final ObjectProvider<WorkerDispatchTool> workerDispatchTool;

    private LoadBalancedWebClientTransport transport;

    @Value("${agent.model.base-url:http://sunshine-llm-gateway/v1}")
    private String modelBaseUrl;
    @Value("${agent.model.max-tokens:16384}")
    private int maxTokens;
    /** Gateway 代理鉴权占位（常为 sunshine-gateway），非上游厂商 key */
    @Value("${agent.model.api-key:}")
    private String apiKey;

    @PostConstruct
    void initTransport() {
        this.transport = new LoadBalancedWebClientTransport(webClientBuilder, "http://sunshine-llm-gateway");
    }

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

    OpenAIChatModel buildModel(AgentRunRequest request) {
        ResolvedModelScene resolved = resolveModel(request);
        if (resolved.overrideInvalid()) {
            log.warn("[ReActAgentFactory] chat model override invalid, using scene primary={}",
                    resolved.effectiveModel());
        }
        String overriddenBaseUrl = modelBaseUrl;
        int resolvedMaxTokens = maxTokens;
        if (request != null && request.modelConfigJson() != null && !request.modelConfigJson().isBlank()
                && !"{}".equals(request.modelConfigJson())) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = MAPPER.readValue(request.modelConfigJson(), Map.class);
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
        if (resolved.extras() != null && resolved.extras().get("max_tokens") instanceof Number n) {
            resolvedMaxTokens = n.intValue();
        }
        // 按注册表模型上限钳制（如 qwen-max 仅允许 ≤8192）
        if (resolved.maxOutputTokens() > 0 && resolvedMaxTokens > resolved.maxOutputTokens()) {
            log.info("[ReActAgentFactory] clamp maxTokens {} → {} for model={}",
                    resolvedMaxTokens, resolved.maxOutputTokens(), resolved.effectiveModel());
            resolvedMaxTokens = resolved.maxOutputTokens();
        }
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(resolved.effectiveModel())
                .baseUrl(overriddenBaseUrl)
                .httpTransport(transport)
                .contextWindowSize(resolved.contextWindow())
                .generateOptions(GenerateOptions.builder().maxTokens(resolvedMaxTokens).build())
                .stream(true)
                .build();
    }

    /**
     * D10：modelConfigJson.model &gt; modelOverride &gt; scene（MAIN=chat / SUB=subagent / PLANNER=planner）。
     */
    ResolvedModelScene resolveModel(AgentRunRequest request) {
        String fromConfig = extractModelFromConfigJson(request != null ? request.modelConfigJson() : null);
        String override = StringUtils.hasText(fromConfig)
                ? fromConfig
                : (request != null ? request.modelOverride() : null);
        AgentRole role = request != null ? request.role() : AgentRole.MAIN;
        if (role == AgentRole.MAIN) {
            // MAIN chat：无效会话模型需 warning 标记
            if (StringUtils.hasText(fromConfig)) {
                return modelSceneResolver.resolve(ModelSceneKey.CHAT.key(), fromConfig);
            }
            return modelSceneResolver.resolveChat(override);
        }
        if (role == AgentRole.SUB || role == AgentRole.WORKER) {
            // WORKER 暂复用 subagent scene（无独立 worker scene）；避免落入 planner
            return modelSceneResolver.resolve(ModelSceneKey.SUBAGENT.key(), override);
        }
        return modelSceneResolver.resolve(ModelSceneKey.PLANNER.key(), override);
    }

    private static String extractModelFromConfigJson(String modelConfigJson) {
        if (modelConfigJson == null || modelConfigJson.isBlank() || "{}".equals(modelConfigJson)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = MAPPER.readValue(modelConfigJson, Map.class);
            if (config.get("model") instanceof String m && !m.isBlank()) {
                return m.strip();
            }
        } catch (Exception ignored) {
        }
        return null;
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
        String conversationKind = resolveConversationKind(request);
        if (request.role() == AgentRole.SUB || request.role() == AgentRole.WORKER) {
            return dynamicToolkitFactory.buildForSubAgent(
                    request.toolWhitelist(), request.tenantId(), request.skillId(), request.userId());
        }
        if (request.role() == AgentRole.PLANNER) {
            Toolkit tk = dynamicToolkitFactory.buildForPlanner(
                    request.tenantId(), request.skillId(), request.userId(), conversationKind);
            // fail-fast：缺 bean 时 getObject 抛 NoSuchBeanDefinitionException，禁止静默跳过
            workerDispatchTool.getObject().registerIntoPlannerToolkit(tk);
            return tk;
        }
        return dynamicToolkitFactory.build(
                request.tenantId(), request.skillId(), request.userId(), conversationKind);
    }

    /** 优先读 request 透传的 conversationKind；缺省 chat（不按 executionMode、不查库） */
    private static String resolveConversationKind(AgentRunRequest request) {
        if (request != null && StringUtils.hasText(request.conversationKind())) {
            return request.conversationKind().strip();
        }
        return "chat";
    }

    public int resolveMaxIters(AgentRunRequest request) {
        if (request.maxIters() > 0) {
            return request.maxIters();
        }
        if (request.role() == AgentRole.WORKER) {
            return executionProperties.getReact().getTaskMaxIters();
        }
        return executionProperties.getReact().getMaxIters();
    }

    private static String resolveAgentName(AgentRunRequest request) {
        if (request.role() == AgentRole.SUB) {
            return "Sunshine-SubAgent";
        }
        if (request.role() == AgentRole.WORKER) {
            return "Sunshine-Worker";
        }
        if (request.role() == AgentRole.PLANNER) {
            return "Sunshine-Planner";
        }
        return "Sunshine-Assistant";
    }
}
