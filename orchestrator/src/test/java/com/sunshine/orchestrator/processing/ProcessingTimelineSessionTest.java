package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void doesNotEmitDuplicateOnSameState() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<ProcessingStep> emitted = new ArrayList<>();
        session.onStepChanged(emitted::add);

        session.start("intent", "intent");
        session.start("intent", "intent");
        session.progress("intent", "正在分析用户输入");
        session.progress("intent", "正在分析用户输入");

        assertEquals(1, emitted.size());
        assertEquals("running", emitted.get(0).lifecycle());
    }
}
