package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.ContextGroupEstimator;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.sandbox.SandboxWriteEditPlaceholderSupport;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AS2 P1：ProcessingStepMiddleware 工厂。P2-1（E5）起 middleware 无状态（bridgeId 经
 * RuntimeContext 注入），全应用共享单实例，供 HarnessAgent 指纹缓存安全复用。
 */
@Component
@RequiredArgsConstructor
public class ProcessingStepMiddlewareFactory {

    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final SandboxWriteEditPlaceholderSupport writeEditPlaceholder;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;
    private final PromptCatalogHolder catalogHolder;
    private final ContextGroupEstimator contextGroupEstimator;
    private final ToolRetrievalMiddleware toolRetrievalMiddleware;
    private final SkillCatalogService skillCatalogService;

    private volatile MiddlewareBase shared;
    private volatile List<MiddlewareBase> sharedChain;

    /** 共享无状态实例（bridgeId per-call 注入，非构造参数） */
    public MiddlewareBase shared() {
        MiddlewareBase s = shared;
        if (s == null) {
            synchronized (this) {
                if (shared == null) {
                    shared = new ProcessingStepMiddleware(
                            toolCatalogService,
                            executionProperties,
                            taskBoardTimelineSupport,
                            sandboxTimelineLabels,
                            writeEditPlaceholder,
                            cancellableToolRunRegistry,
                            catalogHolder,
                            contextGroupEstimator);
                }
                s = shared;
            }
        }
        return s;
    }

    /**
     * 4.7.7 组合中间件链：GoalAlignment → ProcessingStep → FailureBudget → ToolRetrieval → SkillInjection。
     * AS2 {@code MiddlewareChain} 洋葱序：first=最外层。onReasoning 由外到内执行 →
     * goal-check 先注入、budget 后注入（spec §4.3 贴近模型注意力末端）；ToolRetrieval 最内层
     * 在核心 reasoning 前按上下文检索 Top-K 并更新激活组（本轮决定下一轮 tools schema）。
     * onActing 事件由内到外流出 → FailureBudget 最内层先收到 ToolResultEndEvent 并标记
     * budgetExceededToolUseIds，随后 PSM 的 completeToolStep 查询到该标记，达阈值那次
     * tool 步 after 才换「连续失败，需调整方案」（spec §5.4）。若 PSM 置于最内层，
     * completeToolStep 先于预算标记执行，文案替换永不生效。
     * SkillInjection 置于最内层，onSystemPrompt 最后执行——把触发集 skill 正文追加到 system prompt
     * （SYSTEM 权威层，AS 2.0 官方通道），替代 USER 信封注入。
     */
    public List<MiddlewareBase> sharedChain() {
        List<MiddlewareBase> chain = sharedChain;
        if (chain == null) {
            synchronized (this) {
                if (sharedChain == null) {
                    sharedChain = List.of(
                            new GoalAlignmentMiddleware(executionProperties, catalogHolder),
                            shared(),
                            new FailureBudgetMiddleware(executionProperties, catalogHolder),
                            toolRetrievalMiddleware,
                            new SkillInjectionMiddleware(skillCatalogService));
                }
                chain = sharedChain;
            }
        }
        return chain;
    }
}
