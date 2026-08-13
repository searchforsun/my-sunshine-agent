package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerContextFactoryTest {

    private static final String WORKER_TEMPLATE = """
            你是 Worker。
            ## 当前单元契约
            - **目标**：{{taskGoal}}
            - **约束**：{{constraints}}
            - **期望产出**：{{expectedOutput}}
            - **成功标准**：{{successCriteria}}
            （上游依赖 handoff 由平台按 dependsOn 注入 query 动态段，此处不重复。）
            """;

    private PromptCatalogHolder catalogHolder;
    private WorkerContextFactory factory;
    private AgentExecutionProperties.Harness harness;

    @BeforeEach
    void setUp() {
        catalogHolder = new PromptCatalogHolder();
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry(
                        "harness.worker", "harness", "Worker", true, 0, 1,
                        WORKER_TEMPLATE, null))));
        factory = new WorkerContextFactory(catalogHolder);
        harness = new AgentExecutionProperties().getHarness();
    }

    @Test
    void stablePrefixIgnoresUpstreamChanges() {
        PlanNotebook nb1 = PlanNotebook.create("goal", "query", "task", 12, 24);
        TaskItem upstream1 = new TaskItem("u1", "上游A", "done", List.of(), "", "", "");
        nb1.getTaskQueue().add(upstream1);
        nb1.appendRound(new RoundRecord(0, upstream1, List.of(new NodeResult("u1", "done", "handoff-AAA")), 0.2, "ok"));

        PlanNotebook nb2 = PlanNotebook.create("goal", "query", "task", 12, 24);
        TaskItem upstream2 = new TaskItem("u1", "上游A", "done", List.of(), "", "", "");
        nb2.getTaskQueue().add(upstream2);
        nb2.appendRound(new RoundRecord(0, upstream2, List.of(new NodeResult("u1", "done", "handoff-BBB-DIFFERENT")), 0.2, "ok"));

        TaskItem worker = new TaskItem(
                "t2", "分析代码", "pending", List.of("u1"),
                "只读", "报告", "有结论");
        nb1.getTaskQueue().add(worker);
        nb2.getTaskQueue().add(worker);

        List<String> whitelist = List.of("sandbox__exec", "search_knowledge");
        AssembledContext ctx1 = factory.build(nb1, worker, harness, whitelist);
        AssembledContext ctx2 = factory.build(nb2, worker, harness, whitelist);

        assertThat(ctx1.projectGuideBlock()).isEqualTo(ctx2.projectGuideBlock());
        assertThat(ctx1.projectGuideBlock()).contains("分析代码", "只读", "报告", "有结论");
        assertThat(ctx1.projectGuideBlock()).contains("sandbox__exec", "search_knowledge");
        assertThat(ctx1.projectGuideBlock()).doesNotContain("handoff-AAA", "handoff-BBB");
        assertThat(ctx1.l2SystemBlock()).isEmpty();
        assertThat(ctx1.nearTurns()).isEmpty();
    }

    @Test
    void threeArgBuildMatchesEmptyWhitelistNote() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        TaskItem task = new TaskItem("t1", "调研", "pending", List.of(), "c", "e", "s");
        AssembledContext ctx = factory.build(nb, task, harness);
        assertThat(ctx.projectGuideBlock()).contains("调研", "c", "e", "s");
        assertThat(ctx.projectGuideBlock()).contains("工具白名单");
    }

    @Test
    void dynamicQueryContainsUpstreamHandoffs_notInStablePrefix() {
        PlanNotebook nb = PlanNotebook.create("goal", "用户问题X", "task", 12, 24);
        TaskItem u1 = new TaskItem("u1", "上游", "done", List.of(), "", "", "");
        nb.getTaskQueue().add(u1);
        nb.appendRound(new RoundRecord(0, u1, List.of(new NodeResult("u1", "done", "上游结论Z")), 0.3, "ok"));
        TaskItem worker = new TaskItem("t2", "下游", "pending", List.of("u1"), "", "", "");

        AssembledContext ctx = factory.build(nb, worker, harness, List.of("sandbox__exec"));
        String query = factory.buildDynamicQuery(nb, worker);

        assertThat(ctx.projectGuideBlock()).doesNotContain("上游结论Z");
        assertThat(query).contains("上游结论Z");
        assertThat(query).contains("u1");
        assertThat(query).contains("用户问题X");
    }

    @Test
    void dynamicQuerySkipsMissingDependencies() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        TaskItem worker = new TaskItem("t2", "下游", "pending", List.of("missing"), "", "", "");
        String query = factory.buildDynamicQuery(nb, worker);
        assertThat(query).doesNotContain("missing");
        assertThat(query).contains("q");
    }
}
