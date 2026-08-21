package com.sunshine.orchestrator.plan.harness;

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

    @BeforeEach
    void setUp() {
        catalogHolder = new PromptCatalogHolder();
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry(
                        "harness.worker", "harness", "Worker", true, 0, 1,
                        WORKER_TEMPLATE, null))));
        factory = new WorkerContextFactory(catalogHolder);
    }

    @Test
    void stablePrefixListsTaskContractOnly() {
        TaskItem worker = TaskItem.initial("t2", "分析代码", List.of("u1"), "只读", "报告", "有结论");

        AssembledContext ctx = factory.build(worker);

        assertThat(ctx.projectGuideBlock()).contains("分析代码", "只读", "报告", "有结论");
        // 工具白名单由 AgentRunRequest.toolWhitelist 在运行时控制注册，prompt 不再枚举
        assertThat(ctx.projectGuideBlock()).doesNotContain("工具白名单");
        assertThat(ctx.l2SystemBlock()).isEmpty();
        assertThat(ctx.nearTurns()).isEmpty();
    }

    @Test
    void dynamicQueryContainsUpstreamHandoffs_notInStablePrefix() {
        PlanNotebook nb = PlanNotebook.create("goal", "用户问题X", "task", 12, 24);
        TaskItem u1 = TaskItem.initial("u1", "上游", List.of(), "", "", "").withStatus("done", null);
        nb.getTaskQueue().add(u1);
        nb.appendRound(new RoundRecord(0, u1, List.of(new NodeResult("u1", "done", "上游结论Z")), 0.3, "ok"));
        TaskItem worker = TaskItem.initial("t2", "下游", List.of("u1"), "", "", "");

        AssembledContext ctx = factory.build(worker);
        String query = factory.buildDynamicQuery(nb, worker);

        assertThat(ctx.projectGuideBlock()).doesNotContain("上游结论Z");
        assertThat(query).contains("上游结论Z");
        assertThat(query).contains("u1");
        assertThat(query).contains("用户问题X");
    }

    @Test
    void dynamicQuerySkipsMissingDependencies() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        TaskItem worker = TaskItem.initial("t2", "下游", List.of("missing"), "", "", "");
        String query = factory.buildDynamicQuery(nb, worker);
        assertThat(query).doesNotContain("missing");
        assertThat(query).contains("q");
    }
}
