package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTimelineBridgeTest {

    @Test
    void parentStepId_prefixesTaskId() {
        assertThat(WorkerTimelineBridge.parentStepId("t1")).isEqualTo("worker-t1");
        assertThat(WorkerTimelineBridge.parentStepId("worker-t1")).isEqualTo("worker-t1");
        assertThat(WorkerTimelineBridge.parentStepId(null)).isEqualTo("worker-unknown");
    }

    @Test
    void begin_emitsRunningSkeleton() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> tokens = bridge.begin();

        ProcessingStep step = stepOf(tokens);
        assertThat(step.id()).isEqualTo("worker-t1");
        assertThat(step.phase()).isEqualTo("worker");
        assertThat(step.lifecycle()).isEqualTo("running");
        assertThat(step.label()).isEqualTo("调研仓库");
        assertThat(step.summary()).isNotNull();
        // 终态主行只展示任务名（label），不再下发「执行中」后缀（状态由 chevron / spinner 表达）
        assertThat(step.summary().active()).isNull();
    }

    @Test
    void wrap_foldsInternalStepIntoSubSteps() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> updated = bridge.wrap(
                StreamToken.step(ProcessingStep.running("think", "think", "思考过程")));

        assertThat(updated).hasSize(1);
        ProcessingStep parent = updated.get(0).step();
        assertThat(parent.id()).isEqualTo("worker-t1");
        assertThat(parent.lifecycle()).isEqualTo("running");
        assertThat(parent.subSteps()).isNotNull();
        assertThat(parent.subSteps()).extracting(ProcessingStep::id).containsExactly("think");
    }

    @Test
    void wrap_ignoresReasoning() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");

        List<StreamToken> tokens = bridge.wrap(StreamToken.reasoning("推理片段"));

        assertThat(tokens).isEmpty();
        assertThat(bridge.subSteps()).isEmpty();
    }

    @Test
    void wrap_routesPlainContentToParentResultDelta() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> tokens = bridge.wrap(StreamToken.content("正文片段"));

        // 未分段 plain content -> step_delta(result) 到父步，前端 worker 行正文流式累积
        assertThat(tokens).hasSize(1);
        StreamToken delta = tokens.get(0);
        assertThat(delta.isStepDelta()).isTrue();
        assertThat(delta.stepId()).isEqualTo("worker-t1");
        assertThat(delta.channel()).isEqualTo("result");
        assertThat(delta.text()).isEqualTo("正文片段");
    }

    @Test
    void wrap_routesSegmentedContentLifecycleToParentScope() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> routed = bridge.wrap(StreamToken.contentInSegment("content-1", "分段正文"));

        // 分段 content -> scope 重定向到父步（前端经 contentBlocks 穿插渲染）
        assertThat(routed).hasSize(1);
        assertThat(routed.get(0).scopeNodeStepId()).isEqualTo("worker-t1");
        assertThat(routed.get(0).text()).isEqualTo("分段正文");
    }

    @Test
    void wrap_preservesNestedSubagentContentScope() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");
        // 子 Agent 正文经 SpawnSubagentTimelineBridge 折叠后已带 scope=subagent-{runId}，
        // Worker 桥 wrapper 必须透传保持原 scope（前端挂到 subagent 卡 contentBlocks），
        // 否则三个子 agent 的分段正文并发流式全部混入 worker 抽屉正文（碎片拼接）。
        StreamToken subContent = StreamToken.contentInSegment("content-1", "子代理分段正文")
                .withScopeNodeStepId("subagent-abc");

        List<StreamToken> routed = bridge.wrap(subContent);

        assertThat(routed).hasSize(1);
        assertThat(routed.get(0).scopeNodeStepId()).isEqualTo("subagent-abc");
        assertThat(routed.get(0).text()).isEqualTo("子代理分段正文");
        assertThat(bridge.subSteps()).isEmpty();
    }

    @Test
    void wrap_stepDelta_throttlesBurstSnapshots() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");
        bridge.begin();
        // 结构变化（think step）立即快照
        List<StreamToken> stepSnap = bridge.wrap(
                StreamToken.step(ProcessingStep.running("think", "think", "思考")));
        assertThat(stepSnap).hasSize(1);
        // 节流窗口内的连续 reasoning 增量：不再重复下发父步快照，仅合并 subSteps
        List<StreamToken> burst1 = bridge.wrap(StreamToken.stepDelta("think", "reasoning", "片一"));
        List<StreamToken> burst2 = bridge.wrap(StreamToken.stepDelta("think", "reasoning", "片二"));
        assertThat(burst1).isEmpty();
        assertThat(burst2).isEmpty();
        assertThat(bridge.subSteps()).hasSize(1);
    }

    @Test
    void wrap_stepDelta_emitsAfterThrottleWindow() throws InterruptedException {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");
        bridge.begin();
        bridge.wrap(StreamToken.step(ProcessingStep.running("think", "think", "思考")));
        bridge.wrap(StreamToken.stepDelta("think", "reasoning", "片一"));
        Thread.sleep(210);

        List<StreamToken> after = bridge.wrap(StreamToken.stepDelta("think", "reasoning", "片二"));

        assertThat(after).hasSize(1);
        ProcessingStep parent = after.get(0).step();
        assertThat(parent.lifecycle()).isEqualTo("running");
        assertThat(parent.subSteps()).hasSize(1);
        // 节流期间的增量已合并进快照
        assertThat(parent.subSteps().get(0).reasoning()).contains("片一").contains("片二");
    }

    @Test
    void complete_afterThrottledDeltas_containsFullSubSteps() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");
        bridge.begin();
        bridge.wrap(StreamToken.step(ProcessingStep.running("think", "think", "思考")));
        bridge.wrap(StreamToken.stepDelta("think", "reasoning", "推理内容"));

        List<StreamToken> done = bridge.complete("handoff 文本", true);

        ProcessingStep parent = done.get(0).step();
        assertThat(parent.lifecycle()).isEqualTo("done");
        // 节流窗口内未下发的增量由终态快照兜底，前端最终态完整
        assertThat(parent.subSteps()).hasSize(1);
        assertThat(parent.subSteps().get(0).id()).isEqualTo("think");
        assertThat(parent.subSteps().get(0).reasoning()).isEqualTo("推理内容");
        // 正文终稿落父步 result，不再追加 handoff 子步
        assertThat(parent.result()).isEqualTo("handoff 文本");
    }

    @Test
    void complete_marksParentDoneWithResultOnParentStep() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研仓库");

        List<StreamToken> tokens = bridge.complete("handoff 文本", true);

        // 仅父步 done；正文流式由 wrap 的 content 路由承载，终稿 result 落父步字段兜底
        assertThat(tokens).hasSize(1);
        ProcessingStep parent = tokens.get(0).step();
        assertThat(parent.id()).isEqualTo("worker-t1");
        assertThat(parent.lifecycle()).isEqualTo("done");
        assertThat(parent.result()).isEqualTo("handoff 文本");
        // 主行不展示「完成」状态文案（status 由对勾 / chevron 表达，避免与正文重复）
        assertThat(parent.summary().after()).isNull();
        assertThat(parent.endedAt()).isNotNull();
        // 不再追加「任务结果汇总」handoff 子步（ProcessingStep 紧凑构造器把空 list 转为 null）
        assertThat(parent.subSteps()).isNull();
    }

    @Test
    void complete_error_marksError() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");

        List<StreamToken> tokens = bridge.complete("超时", false);

        assertThat(tokens).hasSize(1);
        ProcessingStep step = tokens.get(0).step();
        assertThat(step.lifecycle()).isEqualTo("error");
        assertThat(step.endedAt()).isNotNull();
    }

    @Test
    void cancel_marksPausedWithCancelledSummary() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研");

        List<StreamToken> tokens = bridge.cancel("用户取消");

        assertThat(tokens).hasSize(1);
        ProcessingStep step = tokens.get(0).step();
        assertThat(step.id()).isEqualTo("worker-t1");
        assertThat(step.lifecycle()).isEqualTo("paused");
        assertThat(step.summary().after()).isEqualTo("已取消");
        assertThat(step.result()).isEqualTo("用户取消");
        assertThat(step.endedAt()).isNotNull();
    }

    @Test
    void taskContract_carriedOnParentMetadataForDrawer() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge(
                "t1", "调研", "## 任务目标\n调研仓库\n## 约束\n只读");

        ProcessingStep parent = stepOf(bridge.begin());

        assertThat(parent.metadata()).isNotNull();
        assertThat(parent.metadata().spawnPrompt()).contains("任务目标").contains("调研仓库");
    }

    @Test
    void taskContract_empty_omitsMetadata() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研", "");

        ProcessingStep parent = stepOf(bridge.begin());

        assertThat(parent.metadata()).isNull();
    }

    @Test
    void runId_carriedOnMetadataForCancel() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研", "", "run-abc");

        ProcessingStep parent = stepOf(bridge.begin());

        assertThat(parent.metadata()).isNotNull();
        assertThat(parent.metadata().workerRunId()).isEqualTo("run-abc");
    }

    @Test
    void cancel_keepsWorkerRunIdInMetadata() {
        WorkerTimelineBridge bridge = new WorkerTimelineBridge("t1", "调研", "", "run-abc");

        ProcessingStep parent = stepOf(bridge.cancel("用户取消"));

        assertThat(parent.metadata()).isNotNull();
        assertThat(parent.metadata().workerRunId()).isEqualTo("run-abc");
    }

    private static ProcessingStep stepOf(List<StreamToken> tokens) {
        assertThat(tokens).isNotEmpty();
        return tokens.get(0).step();
    }
}
