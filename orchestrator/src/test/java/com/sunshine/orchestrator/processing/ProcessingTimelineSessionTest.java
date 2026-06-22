package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.AgentRewriteProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingTimelineSessionTest {

    @Test
    void emitsOnPendingStartComplete() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<ProcessingStep> emitted = new ArrayList<>();
        session.onStepChanged(emitted::add);

        session.bindUserQuery("年假政策");
        session.pending("intent", "intent");
        session.start("intent", "intent");
        session.complete("intent", "知识库查询");

        assertEquals(3, emitted.size());
        assertEquals("pending", emitted.get(0).lifecycle());
        assertEquals("running", emitted.get(1).lifecycle());
        assertEquals("done", emitted.get(2).lifecycle());
        assertThat(emitted.get(2).summary().after()).contains("年假政策");
        assertTrue(session.lastChanged().isPresent());
        assertEquals("done", session.lastChanged().get().lifecycle());
    }

    @Test
    void noteToolCallPending_onlyTracksPendingCount() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.openNextThink();
        session.noteToolCallPending();

        assertThat(session.hasPendingToolCalls()).isTrue();
        assertThat(session.isThinkRunning()).isTrue();
    }

    @Test
    void beginEndReasoningRound_closesRunningThink() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.beginReasoningRound();
        session.endReasoningRound();

        assertThat(session.snapshot().stream().filter(s -> "think".equals(s.id())).findFirst().orElseThrow().lifecycle())
                .isEqualTo("done");
        assertThat(session.isThinkRunning()).isFalse();
    }

    @Test
    void openNextThink_createsIncrementalIds() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");

        assertThat(session.openNextThink()).isEqualTo("think");
        session.complete("think", null);

        assertThat(session.openNextThink()).isEqualTo("think-2");
    }

    @Test
    void openNextThink_reusesRunningStep() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.openNextThink();
        String first = session.currentThinkStepId();
        session.openNextThink();
        assertThat(session.currentThinkStepId()).isEqualTo(first);
        assertThat(session.snapshot()).hasSize(1);
    }

    @Test
    void threeToolReactTimeline_oneAnalysisPerTool() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("请自主依次调用三个工具");

        // 规划推理 → 工具1
        session.beginReasoningRound();
        session.endReasoningRound();
        session.noteToolCallPending();
        session.recordToolCompleted("统计财务消息");
        session.noteToolCallDone();

        // 工具1 返回后综合分析
        session.beginReasoningRound();
        session.endReasoningRound();
        ProcessingStep think2 = session.snapshot().stream()
                .filter(s -> "think-2".equals(s.id())).findFirst().orElseThrow();
        assertThat(think2.lifecycle()).isEqualTo("done");
        assertThat(think2.summary().after()).contains("统计财务消息");
        assertThat(think2.summary().after()).doesNotContain("请自主");

        // 综合分析 → 工具2
        session.noteToolCallPending();
        session.recordToolCompleted("查询财务消息详情");
        session.noteToolCallDone();

        // 工具2 返回后综合分析
        session.beginReasoningRound();
        session.endReasoningRound();
        ProcessingStep think3 = session.snapshot().stream()
                .filter(s -> "think-3".equals(s.id())).findFirst().orElseThrow();
        assertThat(think3.summary().after()).contains("查询财务消息详情");

        // 综合分析 → 工具3
        session.noteToolCallPending();
        session.recordToolCompleted("检索知识库");
        session.noteToolCallDone();

        // 最后一轮综合分析
        session.beginReasoningRound();
        session.endReasoningRound();
        ProcessingStep think4 = session.snapshot().stream()
                .filter(s -> "think-4".equals(s.id())).findFirst().orElseThrow();
        assertThat(think4.summary().after()).contains("检索知识库");

        long analysisCount = session.snapshot().stream()
                .filter(s -> s.id().startsWith("think-") && "done".equals(s.lifecycle()))
                .count();
        assertThat(analysisCount).isEqualTo(3);
    }

    @Test
    void completeAt_ragWithTimestampId_summarizesDetail() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("项目预算审批流程");
        String raw = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                表格内容不应出现
                """;
        session.beginToolStep("rag", "rag");
        session.completeToolStep(raw);

        ProcessingStep rag = session.snapshot().stream()
                .filter(s -> ToolStepIds.isRagStep(s.id()))
                .findFirst()
                .orElseThrow();
        assertThat(rag.detail()).isNull();
        assertThat(rag.metadata()).isNotNull();
        assertThat(rag.metadata().hitCount()).isEqualTo(3);
        assertThat(rag.metadata().sources()).containsExactly("公司请假流程规范");
        assertThat(rag.summary().after()).contains("3 条");
        assertThat(rag.summary().after()).doesNotContain("【");
    }

    @Test
    void completeIntent_exposesRewriteDetailWhenProvided() {
        AgentRewriteProperties props = new AgentRewriteProperties();
        AgentRewriteProperties.Timeline timeline = new AgentRewriteProperties.Timeline();
        timeline.setIntent("【意图识别前】短问句补全");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("待审批");
        session.pending("intent", "intent");
        session.start("intent", "intent");

        QueryRewriteOutcome rewrite = QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销", 15L);
        session.completeIntent(
                new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "test"),
                rewrite);

        ProcessingStep intent = session.snapshot().stream()
                .filter(s -> "intent".equals(s.id())).findFirst().orElseThrow();
        assertThat(intent.detail()).contains("【意图识别前】短问句补全");
        assertThat(intent.detail()).contains("改写前：待审批");
        assertThat(intent.metadata().rewriteApplied()).isTrue();
        assertThat(intent.metadata().rewriteLatencyMs()).isEqualTo(15L);
        assertThat(intent.metadata().rewriteScenarioLabel()).isEqualTo("【意图识别前】短问句补全");
        RewriteTimelineLabels.bind(null);
    }

    @Test
    void completeAt_workflowRagNode_keepsHitDetailWithoutRewrite() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("报销呢？");
        session.bindTraceMessageId("msg-1");
        session.pending("node-rag", "node");
        session.start("node-rag", "node");

        session.completeAt("node-rag", "命中 3 条，来源：公司报销管理制度", "命中 3 条，来源：公司报销管理制度",
                System.currentTimeMillis());

        ProcessingStep rag = session.snapshot().stream()
                .filter(s -> "node-rag".equals(s.id())).findFirst().orElseThrow();
        assertThat(rag.detail()).isEqualTo("命中 3 条，来源：公司报销管理制度");
        assertThat(rag.metadata()).isNotNull();
        assertThat(rag.metadata().hitCount()).isEqualTo(3);
        assertThat(rag.metadata().sources()).containsExactly("公司报销管理制度");
    }

    @Test
    void completeAt_workflowRagNode_mergesRewriteAndHitDetail() {
        AgentRewriteProperties props = new AgentRewriteProperties();
        AgentRewriteProperties.Timeline timeline = new AgentRewriteProperties.Timeline();
        timeline.setIntent("【意图识别前】短问句补全");
        timeline.setRag("【知识库检索前】优化检索词");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("报差旅");
        session.bindTraceMessageId("msg-2");
        QueryRewriteTrace.bind("msg-2");
        QueryRewriteTrace.record("msg-2",
                QueryRewriteOutcome.of("intent", "报差旅", "查询差旅报销制度", 12L));
        QueryRewriteTrace.record("msg-2",
                QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 9L));
        session.pending("node-rag", "node");
        session.start("node-rag", "node");

        session.completeAt("node-rag", "命中 2 条，来源：公司差旅费报销管理办法", "命中 2 条，来源：公司差旅费报销管理办法",
                System.currentTimeMillis());

        ProcessingStep rag = session.snapshot().stream()
                .filter(s -> "node-rag".equals(s.id())).findFirst().orElseThrow();
        assertThat(rag.detail()).contains("【知识库检索前】优化检索词");
        assertThat(rag.detail()).contains("改写前：报差旅");
        assertThat(rag.detail()).doesNotContain("【意图识别前】");
        assertThat(rag.detail()).contains("命中 2 条，来源：公司差旅费报销管理办法");
        assertThat(rag.metadata().rewriteApplied()).isTrue();
        assertThat(rag.metadata().hitCount()).isEqualTo(2);
        QueryRewriteTrace.clear("msg-2");
        RewriteTimelineLabels.bind(null);
    }

    @Test
    void completeIntent_keepsAfterWithoutExpandableDetail() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("项目能否按时交付");
        session.pending("intent", "intent");
        session.start("intent", "intent");

        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test");
        IntentLabels.bind(new IntentLabelService(
                new AgentPromptProperties(),
                buildWorkflowPropsForIntent(),
                new WorkflowNodeLabelService(buildWorkflowPropsForIntent(),
                        org.mockito.Mockito.mock(com.sunshine.orchestrator.catalog.ToolCatalogService.class))));
        try {
            session.completeIntent(plan);
            ProcessingStep intent = session.snapshot().stream()
                    .filter(s -> "intent".equals(s.id())).findFirst().orElseThrow();
            assertThat(intent.summary().after()).contains("财务智能分析");
            assertThat(intent.detail()).isNull();
        } finally {
            IntentLabels.bind(null);
        }
    }

    private static WorkflowProperties buildWorkflowPropsForIntent() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.CatalogEntry entry = new WorkflowProperties.CatalogEntry();
        entry.setId("finance-smart");
        entry.setDisplayName("财务智能分析");
        props.setCatalog(List.of(entry));
        props.setDefinitions(new LinkedHashMap<>());
        return props;
    }

    @Test
    void doesNotEmitDuplicateOnSameState() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<ProcessingStep> emitted = new ArrayList<>();
        session.onStepChanged(emitted::add);

        session.start("intent", "intent");
        session.start("intent", "intent");
        session.progress("intent", "正在匹配处理方式");
        session.progress("intent", "正在匹配处理方式");

        assertEquals(1, emitted.size());
        assertEquals("running", emitted.get(0).lifecycle());
    }

    @Test
    void repeatedToolInvocation_createsSeparateStepsWithTimestampId() throws InterruptedException {
        com.sunshine.orchestrator.catalog.ToolCatalogService catalogService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.catalog.ToolCatalogService.class);
        org.mockito.Mockito.when(catalogService.displayName("summarize_finance_by_status"))
                .thenReturn("统计财务消息");
        StepLabels.bind(catalogService);
        try {
            ProcessingTimelineSession session = new ProcessingTimelineSession();
            String base = "tool-summarize_finance_by_status";

            session.beginToolStep(base, "tool");
            session.completeToolStep("pending 3 条，合计 ¥124140.5");
            Thread.sleep(2);
            session.beginToolStep(base, "tool");
            session.completeToolStep("approved 2 条，合计 ¥47199.0");

            List<ProcessingStep> toolSteps = session.snapshot().stream()
                    .filter(s -> s.id().startsWith(base + "@"))
                    .toList();
            assertThat(toolSteps).hasSize(2);
            assertThat(toolSteps.get(0).id()).isNotEqualTo(toolSteps.get(1).id());
            assertThat(toolSteps.get(0).detail()).contains("pending").doesNotContain("·");
            assertThat(toolSteps.get(1).detail()).contains("approved").doesNotContain("·");
            assertThat(toolSteps.get(0).label()).isEqualTo("调用工具 统计财务消息");
        } finally {
            StepLabels.bind(null);
        }
    }
}
