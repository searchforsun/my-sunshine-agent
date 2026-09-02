package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.DecisionOption;
import com.sunshine.orchestrator.agent.DecisionQuestion;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
import com.sunshine.orchestrator.execution.DecisionResumeSteps;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HarnessPlannerTest {

    @Mock
    private AgentRuntime agentRuntime;
    @Mock
    private ContextAssembler contextAssembler;
    @Mock
    private ToolSetResolver toolSetResolver;
    @Mock
    private PromptCatalogHolder catalogHolder;
    @Mock
    private ObjectProvider<GenerationRegistry> generationRegistry;

    private AgentExecutionProperties executionProperties;
    private HarnessPlanner planner;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        executionProperties.getHarness().getPlanner().setMaxIters(20);
        planner = new HarnessPlanner(agentRuntime, contextAssembler, executionProperties, toolSetResolver, catalogHolder, generationRegistry);
        org.mockito.Mockito.lenient().when(contextAssembler.assemble(any()))
                .thenReturn(AssembledContext.empty());
        org.mockito.Mockito.lenient().when(toolSetResolver.resolveDefaultTools(eq("tenant-1"), eq("task")))
                .thenReturn(List.of("sandbox__exec", "search_knowledge"));
        org.mockito.Mockito.lenient().when(generationRegistry.getIfAvailable()).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        WorkerDispatchTool.clearAllSessionsForTests();
    }

    /**
     * Planner run 一次性执行：ReAct 流中调 plan_submit → taskQueue 写入 → 流继续 →
     * 流到正文即结束。验证 plan_submit 工具在 run 期间生效（不再需要信号重试）。
     */
    @Test
    void runPlanned_writesTaskQueueWhenPlannerCallsPlanSubmit() {
        PlannerActionTool actionTool = new PlannerActionTool();
        PlanNotebook nb = PlanNotebook.create("完成调研", "帮我调研仓库", "task", 12, 24);
        when(agentRuntime.run(any())).thenAnswer(inv -> {
            WorkerDispatchTool.DispatchSession session = WorkerDispatchTool.lookupSession("msg-1");
            actionTool.submitPlan(session, List.of(
                    taskArg("t1", "摸底代码库", "只读", "结构摘要", "有目录图")));
            return Flux.just(StreamToken.content("已规划"));
        });

        List<StreamToken> tokens = planner.runPlanned(nb, streamCtx()).collectList().block();

        assertThat(tokens).hasSize(1);
        assertThat(nb.getTaskQueue()).hasSize(1);
        TaskItem task = nb.getTaskQueue().peek();
        assertThat(task.taskId()).isEqualTo("t1");
        assertThat(task.label()).isEqualTo("摸底代码库");
        assertThat(task.status()).isEqualTo("pending");
        assertThat(nb.getRounds()).isEmpty();
        // session 已在 runPlanned 终止时清理
        assertThat(WorkerDispatchTool.lookupSession("msg-1")).isNull();
    }

    @Test
    void runPlanned_streamsContentTokensAsFinalAnswer() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.just(
                StreamToken.content("综合结论：仓库结构清晰。"),
                StreamToken.content(" 进一步分析如下...")));

        List<StreamToken> tokens = planner.runPlanned(nb, streamCtx()).collectList().block();

        assertThat(tokens).hasSize(2);
        assertThat(tokens.stream().anyMatch(t -> t.text() != null && t.text().contains("综合结论")))
                .isTrue();
        // 无 decision 步时不应 bind（新 Planner run 走普通 ReAct）
        assertThat(DecisionResumeSteps.take("msg-1")).isEmpty();
    }

    @Test
    void runPlanned_withExistingDecisionStep_bindsDecisionResumeSteps() {
        // D12：Planner 续跑 existingStepsJson 含 awaiting/paused decision 卡 → bind DecisionResumeSteps，
        // ReActAgentRuntime bridge bind 后 take 到并 re-await 同问卷（契约同 Chat MAIN）。
        PlanNotebook nb = PlanNotebook.create("goal", "继续处理", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("已规划")));

        DecisionStepMeta decision = new DecisionStepMeta(
                "decision-abc",
                "执行方案确认",
                List.of(new DecisionQuestion("q1", "选择执行方案", List.of(
                        new DecisionOption("a", "方案A"),
                        new DecisionOption("b", "方案B")), false)),
                System.currentTimeMillis() + 60_000L,
                null,
                null);
        ProcessingStep decisionStep = ProcessingStep.running("decision-abc", "decision", "执行方案确认")
                .withMetadata(StepMetadata.withDecision(null, decision));
        String stepsJson = ProcessingStepSerde.toJson(List.of(decisionStep));
        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "conv-1", "msg-1", "继续处理", null,
                null, null, "user-1", "tenant-1", null, null, null, null,
                false, false, stepsJson, null, "task");

        planner.runPlanned(nb, ctx).collectList().block();

        // bind 生效：runtime 侧 take 到完整 decision 步（含 metadata.decision 问卷）
        List<ProcessingStep> taken = DecisionResumeSteps.take("msg-1");
        assertThat(taken).isNotEmpty();
        assertThat(taken.get(0).phase()).isEqualTo("decision");
        assertThat(taken.get(0).metadata()).isNotNull();
        assertThat(taken.get(0).metadata().decision()).isNotNull();
        assertThat(taken.get(0).metadata().decision().token()).isEqualTo("decision-abc");
    }

    @Test
    void runPlanned_injectsH1AndPlannerHarnessCatalogId() {
        PlanNotebook nb = PlanNotebook.create("完成调研", "帮我调研仓库", "task", 12, 24);
        PlannerActionTool actionTool = new PlannerActionTool();
        when(agentRuntime.run(any())).thenAnswer(inv -> {
            WorkerDispatchTool.DispatchSession session = WorkerDispatchTool.lookupSession("msg-1");
            actionTool.submitPlan(session, List.of(taskArg("t1", "摸底", "", "", "")));
            return Flux.just(StreamToken.content("已规划"));
        });

        planner.runPlanned(nb, streamCtx()).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.role()).isEqualTo(AgentRole.PLANNER);
        assertThat(req.harnessPromptId()).isEqualTo(HarnessPlanner.CATALOG_ID);
        assertThat(req.injectedBlocks()).isNotEmpty();
        assertThat(req.injectedBlocks().get(0)).contains("## Goal").contains("完成调研");
        // Planner maxIters 已注入（Nacos 默认 30；此处 setMaxIters(20)）
        assertThat(req.maxIters()).isEqualTo(20);
        verify(toolSetResolver).resolveDefaultTools("tenant-1", "task");
    }

    @Test
    void runPlanned_bindsWorkerWhitelistFromToolSetResolver() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        PlannerActionTool actionTool = new PlannerActionTool();
        java.util.concurrent.atomic.AtomicReference<WorkerDispatchTool.DispatchSession> duringRun =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(agentRuntime.run(any())).thenAnswer(inv -> {
            WorkerDispatchTool.DispatchSession session = WorkerDispatchTool.lookupSession("msg-1");
            duringRun.set(session);
            actionTool.submitPlan(session, List.of(taskArg("t1", "步", "", "", "")));
            return Flux.just(StreamToken.content("已规划"));
        });

        planner.runPlanned(nb, streamCtx()).collectList().block();

        assertThat(duringRun.get()).isNotNull();
        assertThat(duringRun.get().toolWhitelist()).containsExactly("sandbox__exec", "search_knowledge");
        // chat 工具解析器不应被调用（Planner 只用 default tools；Worker 按 whitelist 进一步收敛）
        verify(toolSetResolver, never()).resolveChatTools("tenant-1");
    }

    @Test
    void runPlanned_propagatesRuntimeErrors() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.error(new RuntimeException("LLM 异常")));

        Throwable caught = null;
        try {
            planner.runPlanned(nb, streamCtx()).collectList().block();
        } catch (Throwable t) {
            caught = t;
        }

        assertThat(caught).isNotNull();
        assertThat(caught.getMessage()).contains("LLM 异常");
        verify(agentRuntime, times(1)).run(any());
    }

    private static ExecutionStreamContext streamCtx() {
        return new ExecutionStreamContext(
                "conv-1", "msg-1", "帮我调研仓库", null,
                null, null, "user-1", "tenant-1", null);
    }

    private static java.util.Map<String, Object> taskArg(
            String taskId, String label, String constraints, String expectedOutput, String successCriteria) {
        return java.util.Map.of(
                "taskId", taskId,
                "label", label,
                "dependsOn", List.of(),
                "constraints", constraints,
                "expectedOutput", expectedOutput,
                "successCriteria", successCriteria);
    }
}
