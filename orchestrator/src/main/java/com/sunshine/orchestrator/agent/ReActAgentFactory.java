package com.sunshine.orchestrator.agent;

import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.transport.LoadBalancedWebClientTransport;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.plan.harness.PlannerActionTool;
import com.sunshine.orchestrator.plan.harness.WorkerDispatchTool;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.registry.ResolvedModelScene;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
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
 * base system-prompt 经 {@link ReActSystemPromptResolver} 解析（与 runtime 分组估算共用 SSOT）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgentFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReActSystemPromptResolver systemPromptResolver;
    private final AgentExecutionProperties executionProperties;
    private final DynamicToolkitFactory dynamicToolkitFactory;
    private final ProcessingStepMiddlewareFactory middlewareFactory;
    private final AgentStateStore stateStore;
    /** @LoadBalanced WebClient.Builder 由 sunshine-common 自动注入，走 Nacos 服务发现 */
    private final WebClient.Builder webClientBuilder;
    private final ModelSceneResolver modelSceneResolver;
    /** 惰性注入，避免 Factory → DispatchTool → AgentRuntime → Factory 环 */
    private final ObjectProvider<WorkerDispatchTool> workerDispatchTool;
    /** 惰性注入，避免 Factory → ActionTool → AgentRuntime → Factory 环 */
    private final ObjectProvider<PlannerActionTool> plannerActionTool;

    @Value("${agent.model.base-url:http://sunshine-llm-gateway/v1}")
    private String modelBaseUrl;
    @Value("${agent.model.max-tokens:16384}")
    private int maxTokens;
    /** Gateway 代理鉴权占位（常为 sunshine-gateway），非上游厂商 key */
    @Value("${agent.model.api-key:}")
    private String apiKey;

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
                // 工具执行配置：AS 2.0 静态默认给每个工具调用 5min 超时 + 失败重试 3 次，
                // 会在 300s 整点中断 spawn/沙箱长命令等长工具且表象为「已取消」。
                // 工具墙钟由项目分层持有（spawn subagent.timeout-ms、async-tool.exec-wall-timeout-sec、
                // harness.worker.timeout-ms），重试语义由各工具/客户端自持，故此处置空整层。
                .toolExecutionConfig(ExecutionConfig.builder().build())
                .stateStore(stateStore)
                .enablePendingToolRecovery(true)
                .middlewares(middlewareFactory.sharedChain());
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
        // 注册表 request_extras SSOT 键 = max_completion_tokens（OpenAIChatModel 上游契约），
        // 思考型模型经此声明各自的输出预算（如 glm-5.3-flash 128000），不依赖全局 max-tokens
        if (resolved.extras() != null && resolved.extras().get("max_completion_tokens") instanceof Number n) {
            resolvedMaxTokens = n.intValue();
        }
        // 按注册表模型上限钳制（如 qwen-max 仅允许 ≤8192）
        if (resolved.maxOutputTokens() > 0 && resolvedMaxTokens > resolved.maxOutputTokens()) {
            log.info("[ReActAgentFactory] clamp maxTokens {} → {} for model={}",
                    resolvedMaxTokens, resolved.maxOutputTokens(), resolved.effectiveModel());
            resolvedMaxTokens = resolved.maxOutputTokens();
        }
        // 每个 Agent 运行独立 transport：按角色注入 call_site（5.3 用量/路由维度）；
        // session_model 兜底仅在 agent 未显式配置模型时携带（配置模型优先于会话选择，不传递混淆信号）
        String callSite = resolveCallSite(request);
        String sessionModel = request != null
                && (request.modelConfigJson() == null || request.modelConfigJson().isBlank()
                || "{}".equals(request.modelConfigJson()))
                ? request.modelOverride()
                : null;
        LoadBalancedWebClientTransport roleTransport =
                new LoadBalancedWebClientTransport(webClientBuilder, "http://sunshine-llm-gateway",
                        callSite, sessionModel);
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(resolved.effectiveModel())
                .baseUrl(overriddenBaseUrl)
                .httpTransport(roleTransport)
                .contextWindowSize(resolved.contextWindow())
                .generateOptions(buildGenerateOptions(
                        resolvedMaxTokens, request != null ? request.reasoningEffort() : null))
                .stream(true)
                .build();
    }

    /** Agent 角色 → call_site（chat|plan|worker|subagent）。 */
    static String resolveCallSite(AgentRunRequest request) {
        if (request == null || request.role() == null) {
            return LlmGatewayClient.CALL_SITE_CHAT;
        }
        return switch (request.role()) {
            case PLANNER -> LlmGatewayClient.CALL_SITE_PLAN;
            case WORKER -> LlmGatewayClient.CALL_SITE_WORKER;
            case SUB -> LlmGatewayClient.CALL_SITE_SUBAGENT;
            case MAIN -> LlmGatewayClient.CALL_SITE_CHAT;
        };
    }

    /**
     * 会话思考深度（仅 MAIN 传入；空/空白则不设该键），
     * 由 AgentScope formatter 写入上游 {@code reasoning_effort}；
     * 缺省时由 llm-gateway 用注册表 request_extras 补齐。
     */
    static GenerateOptions buildGenerateOptions(int maxTokens, String reasoningEffort) {
        GenerateOptions.Builder options = GenerateOptions.builder().maxTokens(maxTokens);
        if (StringUtils.hasText(reasoningEffort)) {
            options.reasoningEffort(reasoningEffort.strip());
        }
        return options.build();
    }

    /**
     * 解析链：modelConfigJson.model（agent 配置，最高位）→ 会话所选模型 → scene primary/fallback →
     * fail-fast；场景按 role 选择（MAIN=chat / SUB、WORKER=subagent / PLANNER=planner）。
     */
    ResolvedModelScene resolveModel(AgentRunRequest request) {
        String fromConfig = extractModelFromConfigJson(request != null ? request.modelConfigJson() : null);
        String sessionModel = request != null ? request.modelOverride() : null;
        AgentRole role = request != null ? request.role() : AgentRole.MAIN;
        if (role == AgentRole.MAIN) {
            // MAIN chat：会话模型无效时保留 warning 标记
            return modelSceneResolver.resolve(ModelSceneKey.CHAT.key(), fromConfig, sessionModel);
        }
        if (role == AgentRole.SUB || role == AgentRole.WORKER) {
            // WORKER 暂复用 subagent scene（无独立 worker scene）；避免落入 planner
            return modelSceneResolver.resolve(ModelSceneKey.SUBAGENT.key(), fromConfig, sessionModel);
        }
        return modelSceneResolver.resolve(ModelSceneKey.PLANNER.key(), fromConfig, sessionModel);
    }

    /** 供 ReActAgentRuntime.resolveModelName 复用，保证 usage 帧模型名与 factory 实际执行模型一致 */
    public static String extractModelFromConfigJson(String modelConfigJson) {
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
        return systemPromptResolver.resolve(request);
    }

    Toolkit resolveToolkit(AgentRunRequest request) {
        String conversationKind = resolveConversationKind(request);
        if (request.role() == AgentRole.WORKER) {
            // v17.12：Worker 独立构建——SUB 基础 + await_tool_run / async_status / spawn_subagent
            return dynamicToolkitFactory.buildForWorker(
                    request.toolWhitelist(), request.tenantId(), request.skillId(), request.userId());
        }
        if (request.role() == AgentRole.SUB) {
            return dynamicToolkitFactory.buildForSubAgent(
                    request.toolWhitelist(), request.tenantId(), request.skillId(), request.userId());
        }
        if (request.role() == AgentRole.PLANNER) {
            Toolkit tk = dynamicToolkitFactory.buildForPlanner(
                    request.tenantId(), request.skillId(), request.userId(), conversationKind);
            // fail-fast：缺 bean 时 getObject 抛 NoSuchBeanDefinitionException，禁止静默跳过
            workerDispatchTool.getObject().registerIntoPlannerToolkit(tk);
            plannerActionTool.getObject().registerIntoPlannerToolkit(tk);
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
