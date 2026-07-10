package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.AgentRewriteProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingTimelineSessionTest {

    @BeforeEach
    void bindTimelineLabels() {
        TimelineLabelTestSupport.bindDefaults();
    }

    @AfterEach
    void unbindTimelineLabels() {
        TimelineLabelTestSupport.unbind();
    }

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
    void startTool_doesNotCompleteRunningGenerate() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("查待办");
        session.pending("generate", "generate");
        session.startAt("generate", "generate", 1000L);
        session.pending("tool-sdk__sunshine-oa__list_oa_tasks@1", "tool");
        session.startAt("tool-sdk__sunshine-oa__list_oa_tasks@1", "tool", 1001L);

        ProcessingStep generate = session.snapshot().stream()
                .filter(s -> "generate".equals(s.id())).findFirst().orElseThrow();
        assertThat(generate.lifecycle()).isEqualTo("running");
    }

    @Test
    void beginReasoningRound_reusesThinkWhenNoToolSinceLastThink() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("查待办");
        session.beginReasoningRound();
        session.endReasoningRound();
        session.beginReasoningRound();
        session.endReasoningRound();
        session.beginReasoningRound();
        session.endReasoningRound();

        assertThat(session.snapshot().stream().filter(s -> ThinkStepIds.isThinkStep(s.id())).count())
                .isEqualTo(1);
        assertThat(session.contentAnchorAfterStepId()).isEqualTo("think");
    }

    @Test
    void contentAnchorAfterStepId_returnsLastDoneThinkAfterTool() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("查待办");
        session.beginReasoningRound();
        session.endReasoningRound();
        session.recordToolCompleted("统计财务消息");
        session.beginReasoningRound();
        session.endReasoningRound();

        assertThat(session.contentAnchorAfterStepId()).isEqualTo("think-2");
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
        timeline.setIntent("补全问句");
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
        assertThat(intent.detail()).contains("补全问句");
        assertThat(intent.detail()).contains("原问题：待审批");
        assertThat(intent.metadata().rewriteApplied()).isTrue();
        assertThat(intent.metadata().rewriteLatencyMs()).isEqualTo(15L);
        assertThat(intent.metadata().rewriteScenarioLabel()).isEqualTo("补全问句");
        RewriteTimelineLabels.bind(null);
    }

    @Test
    void completeIntent_skill5B_exposesRoutingMetadata() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("@finance-analysis 先查制度再分析");
        session.pending("intent", "intent");
        session.start("intent", "intent");
        Map<String, String> params = Map.of(
                SkillBindingOutcome.PARAM_SKILL, "finance-analysis",
                SkillBindingOutcome.PARAM_PLANNER_MODE, SkillBindingOutcome.PLANNER_MODE_SKILL_DRIVEN);
        session.completeIntent(new ExecutionPlan(
                ExecutionMode.PLAN_WORKFLOW, null, params, "skill:@mention:5b-skill-plan"));

        ProcessingStep intent = session.snapshot().stream()
                .filter(s -> "intent".equals(s.id())).findFirst().orElseThrow();
        assertThat(intent.metadata().skillId()).isEqualTo("finance-analysis");
        assertThat(intent.metadata().plannerMode()).isEqualTo("skill-driven");
        assertThat(intent.metadata().routingReason()).contains("5b-skill-plan");
    }

    @Test
    void completeSkillLoad_emitsSkillTimelineAfterLine() {
        com.sunshine.orchestrator.catalog.SkillCatalogService catalog =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.catalog.SkillCatalogService.class);
        org.mockito.Mockito.when(catalog.findIndex("skill-demo")).thenReturn(java.util.Optional.of(
                new com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry(
                        "skill-demo", "测试技能", "desc", 1, true)));
        SkillLoadLabelService labelService = new SkillLoadLabelService(
                catalog, new com.sunshine.orchestrator.config.AgentPromptProperties());
        labelService.init();
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.completeSkillLoad("skill-demo");
        ProcessingStep skill = session.snapshot().stream()
                .filter(s -> "skill".equals(s.id())).findFirst().orElseThrow();
        assertThat(skill.phase()).isEqualTo("skill");
        assertThat(skill.label()).isEqualTo("加载技能");
        assertThat(skill.summary().after()).isEqualTo("@skill-demo 测试技能");
        assertThat(skill.metadata().skillId()).isEqualTo("skill-demo");
        SkillLoadLabels.bind(null);
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
        timeline.setIntent("补全问句");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("报差旅");
        session.bindTraceMessageId("msg-2");
        QueryRewriteTrace.bind("msg-2");
        QueryRewriteTrace.record("msg-2",
                QueryRewriteOutcome.of("intent", "报差旅", "查询差旅报销制度", 12L));
        session.pending("node-rag", "node");
        session.start("node-rag", "node");
        QueryRewriteTrace.record("msg-2",
                QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 9L, "优化检索词"));

        session.completeAt("node-rag", "命中 2 条，来源：公司差旅费报销管理办法", "命中 2 条，来源：公司差旅费报销管理办法",
                System.currentTimeMillis());

        ProcessingStep rag = session.snapshot().stream()
                .filter(s -> "node-rag".equals(s.id())).findFirst().orElseThrow();
        assertThat(rag.detail()).contains("优化检索词");
        assertThat(rag.detail()).contains("原问题：报差旅");
        assertThat(rag.detail()).doesNotContain("补全问句");
        assertThat(rag.detail()).doesNotContain("命中 2 条");
        assertThat(rag.metadata().rewriteApplied()).isTrue();
        assertThat(rag.metadata().hitCount()).isEqualTo(2);
        assertThat(rag.metadata().rewriteInDetail()).isTrue();
        assertThat(rag.metadata().expandSectionTitle()).isEqualTo("检索过程");
        assertThat(rag.summary().after()).contains("2 条");
        QueryRewriteTrace.clear("msg-2");
        RewriteTimelineLabels.bind(null);
    }

    @Test
    void completeToolStep_doubleRag_scopesRewriteDetailPerInvocation() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("报销合规");
        session.bindTraceMessageId("msg-multi");
        QueryRewriteTrace.bind("msg-multi");

        String rag1 = session.beginToolStep("rag", "rag");
        QueryRewriteTrace.record("msg-multi",
                QueryRewriteOutcome.of("rag", "q1", "rewrite-q1", 10L, "优化检索词"));
        QueryRewriteTrace.record("msg-multi",
                QueryRewriteOutcome.of("hyde", "q1", "hyde-doc-1", 20L, "生成参考文档"));
        session.completeToolStep("命中 1 条");

        String rag2 = session.beginToolStep("rag", "rag");
        QueryRewriteTrace.record("msg-multi",
                QueryRewriteOutcome.of("rag", "q2", "rewrite-q2", 11L, "优化检索词"));
        session.completeToolStep("命中 2 条");

        ProcessingStep first = session.snapshot().stream()
                .filter(s -> rag1.equals(s.id())).findFirst().orElseThrow();
        ProcessingStep second = session.snapshot().stream()
                .filter(s -> rag2.equals(s.id())).findFirst().orElseThrow();
        assertThat(first.detail()).contains("rewrite-q1", "hyde-doc-1");
        assertThat(first.detail()).doesNotContain("rewrite-q2", "命中 1 条");
        assertThat(first.metadata().rewriteInDetail()).isTrue();
        assertThat(first.metadata().expandSectionTitle()).isEqualTo("检索过程");
        assertThat(second.detail()).contains("rewrite-q2");
        assertThat(second.detail()).doesNotContain("rewrite-q1", "hyde-doc-1", "命中 2 条");

        QueryRewriteTrace.clear("msg-multi");
    }

    @Test
    void completeToolStep_parallelRagSpan_eachStepGetsOwnRewrite() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindTraceMessageId("msg-par2");
        QueryRewriteTrace.bind("msg-par2");

        String rag1 = session.beginToolStep("rag", "rag");
        String rag2 = session.beginToolStep("rag", "rag");
        String rag3 = session.beginToolStep("rag", "rag");

        QueryRewriteTrace.beginRagSpan(rag1, "msg-par2");
        QueryRewriteTrace.record("msg-par2",
                QueryRewriteOutcome.of("rag", "q1", "rewrite-q1", 10L, "优化检索词"));
        QueryRewriteTrace.endRagSpan(rag1, "msg-par2");

        QueryRewriteTrace.beginRagSpan(rag2, "msg-par2");
        QueryRewriteTrace.record("msg-par2",
                QueryRewriteOutcome.of("rag", "q2", "rewrite-q2", 11L, "优化检索词"));
        QueryRewriteTrace.endRagSpan(rag2, "msg-par2");

        QueryRewriteTrace.beginRagSpan(rag3, "msg-par2");
        QueryRewriteTrace.record("msg-par2",
                QueryRewriteOutcome.of("rag", "q3", "rewrite-q3", 12L, "优化检索词"));
        QueryRewriteTrace.endRagSpan(rag3, "msg-par2");

        session.completeAt(rag1, "命中 1 条", System.currentTimeMillis());
        session.completeAt(rag2, "命中 2 条", System.currentTimeMillis());
        session.completeAt(rag3, "命中 3 条", System.currentTimeMillis());

        ProcessingStep first = session.snapshot().stream()
                .filter(s -> rag1.equals(s.id())).findFirst().orElseThrow();
        ProcessingStep second = session.snapshot().stream()
                .filter(s -> rag2.equals(s.id())).findFirst().orElseThrow();
        ProcessingStep third = session.snapshot().stream()
                .filter(s -> rag3.equals(s.id())).findFirst().orElseThrow();
        assertThat(first.detail()).contains("rewrite-q1").doesNotContain("rewrite-q2", "rewrite-q3");
        assertThat(second.detail()).contains("rewrite-q2").doesNotContain("rewrite-q1", "rewrite-q3");
        assertThat(third.detail()).contains("rewrite-q3").doesNotContain("rewrite-q1", "rewrite-q2");

        QueryRewriteTrace.clear("msg-par2");
    }

    @Test
    void beginToolStep_producesDistinctIdsWithinSameMillisecond() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            ids.add(session.beginToolStep("rag", "rag"));
        }
        assertThat(ids).hasSize(20);
    }

    @Test
    void completeIntent_keepsAfterWithoutExpandableDetail() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("项目能否按时交付");
        session.pending("intent", "intent");
        session.start("intent", "intent");

        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.WORKFLOW, "finance-smart", Map.of(), "test");
        WorkflowNodeLabelService workflowLabels = new WorkflowNodeLabelService(
                buildWorkflowPropsForIntent(),
                org.mockito.Mockito.mock(com.sunshine.orchestrator.catalog.ToolCatalogService.class),
                new AgentPromptProperties());
        WorkflowNodeLabels.bind(workflowLabels);
        IntentLabels.bind(new IntentLabelService(
                new AgentPromptProperties(),
                buildWorkflowPropsForIntent(),
                workflowLabels));
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
    void completePlanAt_attachesPlannerRewriteMetadata() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("帮我查报销");
        session.bindTraceMessageId("msg-plan");
        QueryRewriteTrace.bind("msg-plan");
        QueryRewriteTrace.record("msg-plan",
                QueryRewriteOutcome.of("planner", "帮我查报销", "请规划差旅报销合规审查流程", 120L));
        session.pending("plan", "plan");
        session.start("plan", "plan");
        session.completePlanAt("检索 → 查询 → 分析", "planId=p1|chain=检索 → 查询", System.currentTimeMillis());
        ProcessingStep plan = session.snapshot().stream()
                .filter(s -> "plan".equals(s.id())).findFirst().orElseThrow();
        assertThat(plan.metadata()).isNotNull();
        assertThat(plan.metadata().rewriteApplied()).isTrue();
        assertThat(plan.metadata().rewriteScenario()).isEqualTo("planner");
        assertThat(plan.metadata().rewriteFrom()).isEqualTo("帮我查报销");
        assertThat(plan.metadata().rewriteTo()).isEqualTo("请规划差旅报销合规审查流程");
        QueryRewriteTrace.clear("msg-plan");
    }

    @Test
    void doesNotEmitDuplicateOnSameState() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<ProcessingStep> emitted = new ArrayList<>();
        session.onStepChanged(emitted::add);

        session.bindUserQuery("测试问题");
        String active = TimelineLabels.active("intent", StepSummarizer.clipQuery("测试问题"));
        session.start("intent", "intent");
        session.start("intent", "intent");
        session.progress("intent", active);
        session.progress("intent", active);

        assertEquals(1, emitted.size());
        assertEquals("running", emitted.get(0).lifecycle());
    }

    @Test
    void ensurePlaceholderAfterFirstThink_emitsTasksStepImmediately() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.beginReasoningRound();
        session.endReasoningRound();

        TaskBoardTimelineSupport support = new TaskBoardTimelineSupport();
        support.ensurePlaceholderAfterFirstThink(session);

        ProcessingStep tasks = session.snapshot().stream()
                .filter(s -> TimelineStepId.TASKS.id().equals(s.id())).findFirst().orElseThrow();
        assertThat(tasks.phase()).isEqualTo("tasks");
        assertThat(tasks.lifecycle()).isIn("pending", "running");
        ProcessingStep think = session.snapshot().stream()
                .filter(s -> "think".equals(s.id())).findFirst().orElseThrow();
        assertThat(tasks.startedAt()).isGreaterThan(think.endedAt() != null ? think.endedAt() : 0L);
    }

    @Test
    void ensurePlaceholderAfterFirstThink_skipsAfterSecondThink() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.beginReasoningRound();
        session.endReasoningRound();
        TaskBoardTimelineSupport support = new TaskBoardTimelineSupport();
        support.ensurePlaceholderAfterFirstThink(session);

        session.beginToolStep("tool-sdk__sunshine-finance__list_finance_messages", "tool");
        session.completeToolStep("3 条");
        session.beginReasoningRound();
        session.endReasoningRound();
        support.ensurePlaceholderAfterFirstThink(session);

        long tasksCount = session.snapshot().stream()
                .filter(s -> TimelineStepId.TASKS.id().equals(s.id())).count();
        assertThat(tasksCount).isEqualTo(1);
    }

    @Test
    void updateTaskBoard_emitsTasksStepWithMetadata() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<TaskBoardItemView> items = List.of(
                new TaskBoardItemView("t1", "检索制度", "completed"),
                new TaskBoardItemView("t2", "查询待审批", "in_progress"));
        StepMetadata metadata = StepMetadata.withTasks(items, 2, "1/2 已完成");
        session.updateTaskBoard(TimelineStepId.TASKS.id(), TimelineStepId.TASKS.phase(),
                TaskBoardStepLabels.active("查询待审批"), metadata);

        ProcessingStep tasks = session.snapshot().stream()
                .filter(s -> TimelineStepId.TASKS.id().equals(s.id())).findFirst().orElseThrow();
        assertThat(tasks.phase()).isEqualTo("tasks");
        assertThat(tasks.label()).isEqualTo("任务清单");
        assertThat(tasks.lifecycle()).isEqualTo("running");
        assertThat(tasks.summary().active()).contains("查询待审批");
        assertThat(tasks.metadata().tasks()).hasSize(2);
        assertThat(tasks.metadata().taskRevision()).isEqualTo(2);
        assertThat(tasks.metadata().taskProgress()).isEqualTo("1/2 已完成");
    }

    @Test
    void updateTaskBoard_anchorsAfterThinkEvenWhenManageTasksLate() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.beginReasoningRound();
        session.endReasoningRound();
        ProcessingStep think = session.snapshot().stream()
                .filter(s -> "think".equals(s.id())).findFirst().orElseThrow();
        long thinkEnd = think.endedAt() != null ? think.endedAt() : think.startedAt();

        session.beginToolStep("tool-sdk__sunshine-finance__list_finance_messages", "tool");
        session.completeToolStep("命中 3 条");

        StepMetadata metadata = StepMetadata.withTasks(
                List.of(new TaskBoardItemView("t1", "查询待审批", "in_progress")),
                1, "0/1 已完成");
        session.updateTaskBoard(TimelineStepId.TASKS.id(), TimelineStepId.TASKS.phase(),
                TaskBoardStepLabels.active("查询待审批"), metadata);

        ProcessingStep tasks = session.snapshot().stream()
                .filter(s -> TimelineStepId.TASKS.id().equals(s.id())).findFirst().orElseThrow();
        assertThat(tasks.startedAt()).isLessThanOrEqualTo(session.snapshot().stream()
                .filter(s -> s.id().startsWith("tool-sdk__sunshine-finance__list_finance"))
                .findFirst().orElseThrow().startedAt());
        assertThat(tasks.startedAt()).isGreaterThanOrEqualTo(thinkEnd);
    }

    @Test
    void completeTaskBoard_marksStepDone() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<TaskBoardItemView> items = List.of(
                new TaskBoardItemView("t1", "检索制度", "completed"),
                new TaskBoardItemView("t2", "汇总结论", "completed"));
        StepMetadata metadata = StepMetadata.withTasks(items, 3, "2/2 已完成");
        session.updateTaskBoard(TimelineStepId.TASKS.id(), TimelineStepId.TASKS.phase(),
                TaskBoardStepLabels.active(""), metadata);
        session.completeTaskBoard(TaskBoardStepLabels.allDone(), metadata);

        ProcessingStep tasks = session.snapshot().stream()
                .filter(s -> TimelineStepId.TASKS.id().equals(s.id())).findFirst().orElseThrow();
        assertThat(tasks.lifecycle()).isEqualTo("done");
        assertThat(tasks.summary().after()).isEqualTo("全部任务已完成");
    }

    @Test
    void dismissEmptyPlaceholder_completesTasksStepWithoutMetadata() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.beginReasoningRound();
        session.endReasoningRound();
        TaskBoardTimelineSupport support = new TaskBoardTimelineSupport();
        support.ensurePlaceholderAfterFirstThink(session);

        support.dismissEmptyPlaceholder(session);

        ProcessingStep tasks = session.snapshot().stream()
                .filter(s -> TimelineStepId.TASKS.id().equals(s.id())).findFirst().orElseThrow();
        assertThat(tasks.lifecycle()).isEqualTo("done");
        assertThat(tasks.metadata()).isNull();
    }

    @Test
    void repeatedToolInvocation_createsSeparateStepsWithTimestampId() throws InterruptedException {
        com.sunshine.orchestrator.catalog.ToolCatalogService catalogService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.catalog.ToolCatalogService.class);
        org.mockito.Mockito.when(catalogService.displayName("sdk__sunshine-finance__summarize_finance_by_status"))
                .thenReturn("统计财务消息");
        ToolNodeLabels.bind(new ToolNodeLabelService(new AgentPromptProperties(), catalogService));
        StepLabels.bind(catalogService);
        try {
            ProcessingTimelineSession session = new ProcessingTimelineSession();
            String base = "tool-sdk__sunshine-finance__summarize_finance_by_status";

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
            ToolNodeLabels.bind(null);
        }
    }
}
