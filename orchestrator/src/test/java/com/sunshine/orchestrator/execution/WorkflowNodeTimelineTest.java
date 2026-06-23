package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowNodeTimelineTest {

    @Test
    @DisplayName("agent 节点 complete（expandDetail=null）应下发 done + after")
    void completeAgentNode_emitsDoneWithAfter() {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery("我希望结合各种信息判断项目是否能够按时交付");

        WorkflowNodeTimeline.start(session, "analyze", "agent");

        List<StreamToken> complete = WorkflowNodeTimeline.complete(
                session, "analyze", "agent",
                "基于现有数据，项目存在预算与进度双重风险",
                null,
                1_000L, 22_000L);

        assertThat(complete).isNotEmpty();
        assertThat(complete.stream().map(StreamToken::step).anyMatch(s ->
                "node-analyze".equals(s.id())
                        && "done".equals(s.lifecycle())
                        && s.summary() != null
                        && s.summary().after() != null
                        && !s.summary().after().isBlank())).isTrue();

        var doneStep = complete.stream().map(StreamToken::step)
                .filter(s -> "node-analyze".equals(s.id()))
                .reduce((a, b) -> b)
                .orElseThrow();
        GenerationFlushScheduler scheduler = new GenerationFlushScheduler(mock(), mock());
        String json = scheduler.metaStep(doneStep);
        assertThat(json).contains("\"lifecycle\":\"done\"");
        assertThat(json).contains("预算");
        assertThat(json).contains("\"after\"");
    }

    @Test
    @DisplayName("节点 start 绑定 displayName 时 active/label 不使用内部 id")
    void startWithDisplayName_usesFriendlyActiveAndLabel() {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery("测试");

        WorkflowNodeTimeline.start(session, "n3", "agent", "合规对比分析");

        var step = session.snapshot().stream()
                .filter(s -> "node-n3".equals(s.id()))
                .findFirst()
                .orElseThrow();
        assertThat(step.label()).isEqualTo("合规对比分析");
        assertThat(step.summary().active()).isEqualTo("正在合规对比分析");
    }

    @Test
    @DisplayName("llm 节点 start 时应补全仍 running 的前序 agent 节点")
    void llmStart_autoCompletesRunningAgentNode() {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery("测试问题");

        WorkflowNodeTimeline.start(session, "analyze", "agent");

        List<StreamToken> llmStart = WorkflowNodeTimeline.start(session, "llm", "llm");

        assertThat(llmStart.stream().map(StreamToken::step).anyMatch(s ->
                "node-analyze".equals(s.id()) && "done".equals(s.lifecycle()))).isTrue();
    }
}
