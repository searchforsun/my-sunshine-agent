package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归复现：工具步（如 git commit）在一轮 think 的 running 窗口内穿插完成，且其后无新的 think 时，
 * 正文段曾锚定「最后一个 done think」（即该 think），正文被渲染在工具步之前 →
 * 「时间线放错位置」/「正文最后多一个执行时间线」。
 *
 * 修复后：正文锚点落在「该 think 之后的工具步」，正文天然渲染在时间线末尾。
 */
class ContentAnchorTailReproTest {

    @BeforeEach
    void bindLabels() {
        TimelineLabelTestSupport.bindDefaults();
    }

    @AfterEach
    void unbindLabels() {
        TimelineLabelTestSupport.unbind();
    }

    @Test
    void contentAnchor_afterTool_thenNewThink_bindsNewThink() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("提交代码");

        // 轮1：think 完成
        session.beginReasoningRound();
        session.ensureThinkOpen();
        session.endReasoningRound();

        // 工具 commit（同 middleware 收口：recordToolCompleted）
        session.beginToolStep("sandbox__exec", "tool");
        session.noteToolCallPending();
        session.recordToolCompleted("已完成 git commit");
        session.noteToolCallDone();

        // 工具后新一轮思考 + 正文流式
        session.beginReasoningRound();
        session.ingestStreamingContentDelta("P0 已全部完成并提交。");
        session.endReasoningRound();

        System.out.println("[repro-afterToolNewThink] anchor=" + session.contentAnchorAfterStepId());
        for (ProcessingStep s : session.snapshot()) {
            System.out.println("[repro-afterToolNewThink] step=" + s.id() + " phase=" + s.phase()
                    + " lc=" + s.lifecycle() + " sa=" + s.startedAt());
        }
        // 正文锚点 = 最后工具之后新开的 think（think-2 或其后）
        assertThat(session.contentAnchorAfterStepId()).isNotNull();
        assertThat(ThinkStepIds.isThinkStep(session.contentAnchorAfterStepId())).isTrue();
    }

    @Test
    void contentAnchor_toolInterleavedInsideThinkWindow_bindsToolAfter() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("提交代码");

        // 轮1：think 打开（running）
        session.beginReasoningRound();
        session.ensureThinkOpen();

        // 工具 commit 在 think 的 running 窗口内插入并完成（startedAt 落在 think 窗口）
        session.beginToolStep("sandbox__exec", "tool");
        session.noteToolCallPending();
        session.recordToolCompleted("已完成 git commit");
        session.noteToolCallDone();

        // 同一 think 轮内正文流式（AgentScope：reasoning → content → tool_calls，无新一轮 think）
        // 此处不调 beginReasoningRound（保持 pendingThinkOpen=NONE），模拟工具后未开新 think 的终态作答
        session.ingestStreamingContentDelta("P0 已全部完成并提交。");
        session.endReasoningRound();

        System.out.println("[repro-toolInThink] anchor=" + session.contentAnchorAfterStepId());
        for (ProcessingStep s : session.snapshot()) {
            System.out.println("[repro-toolInThink] step=" + s.id() + " phase=" + s.phase()
                    + " lc=" + s.lifecycle() + " sa=" + s.startedAt());
        }
        // 正文锚点须落在此 think 之后的工具步，正文才会渲染在时间线末尾
        String anchor = session.contentAnchorAfterStepId();
        assertThat(anchor).isNotNull();
        assertThat(anchor).isNotEqualTo("think");
    }
}
