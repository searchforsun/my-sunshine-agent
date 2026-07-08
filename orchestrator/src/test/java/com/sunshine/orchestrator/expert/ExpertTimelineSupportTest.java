package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertTimelineSupportTest {

    @Test
    void speakDone_recordsElapsedDuration() throws InterruptedException {
        ExpertTranscriptEntry entry = new ExpertTranscriptEntry("policy-expert", "制度专家", 1, "分析结论");
        long startedAt = System.currentTimeMillis();
        Thread.sleep(5);
        StreamToken token = ExpertTimelineSupport.speakDone(entry, false, startedAt, entry.content());
        ProcessingStep step = token.step();
        assertThat(step.startedAt()).isEqualTo(startedAt);
        assertThat(step.endedAt()).isNotNull();
        assertThat(step.durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(step.result()).isEqualTo("分析结论");
    }

    @Test
    void conveneDone_recordsElapsedDuration() throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        Thread.sleep(5);
        StreamToken token = ExpertTimelineSupport.conveneDone(startedAt, List.of("制度专家", "财务专家"), "匹配完成");
        ProcessingStep step = token.step();
        assertThat(step.durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(step.summary().after()).contains("制度专家");
    }

    @Test
    void speakDelta_emitsResultChannel() {
        StreamToken token = ExpertTimelineSupport.speakDelta("expert-policy-expert-s1", "逐字");
        assertThat(token.isStepDelta()).isTrue();
        assertThat(token.stepId()).isEqualTo("expert-policy-expert-s1");
        assertThat(token.channel()).isEqualTo("result");
        assertThat(token.text()).isEqualTo("逐字");
    }
}
