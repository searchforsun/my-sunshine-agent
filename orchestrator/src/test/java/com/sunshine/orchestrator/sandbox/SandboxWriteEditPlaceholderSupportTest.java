package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineLabelTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolCallStart 即开 write 步并下发 active；Delta 解析 path 后刷新主行。
 */
class SandboxWriteEditPlaceholderSupportTest {

    private final ToolCatalogService toolCatalog = mock(ToolCatalogService.class);
    private SandboxWriteEditPlaceholderSupport support;
    private ProcessingTimelineSession session;
    private final String bridgeId = "bridge-write-placeholder";

    @BeforeEach
    void setUp() {
        TimelineLabelTestSupport.bindDefaults();
        SandboxTimelineLabelService labels = new SandboxTimelineLabelService(
                com.sunshine.orchestrator.prompt.TimelinePromptCatalog.withDefaults());
        support = new SandboxWriteEditPlaceholderSupport(toolCatalog, labels);
        session = new ProcessingTimelineSession();
        session.bindUserQuery("写大文件");
        when(toolCatalog.timelineStepId(SandboxIds.WRITE)).thenReturn("tool-" + SandboxIds.WRITE);
        when(toolCatalog.timelinePhase(SandboxIds.WRITE)).thenReturn("tool");
        when(toolCatalog.displayName(SandboxIds.WRITE)).thenReturn("写文件");
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
    }

    @AfterEach
    void tearDown() {
        StepEventBridge.clear(bridgeId);
        TimelineLabelTestSupport.unbind();
    }

    @Test
    void start_opensRunningWriteStepWithActivePlaceholder() {
        support.onToolCallStart(bridgeId, "call-1", SandboxIds.WRITE);

        ProcessingStep step = session.snapshot().stream()
                .filter(s -> s.id().startsWith("tool-sandbox__write"))
                .findFirst()
                .orElseThrow();
        assertThat(step.lifecycle()).isEqualTo("running");
        assertThat(step.summary().active()).contains("正在写入");
        assertThat(StepEventBridge.stepIdForToolUse("call-1")).isEqualTo(step.id());
    }

    @Test
    void delta_updatesActiveWhenPathBecomesAvailable() {
        support.onToolCallStart(bridgeId, "call-2", SandboxIds.WRITE);
        support.onToolCallDelta(bridgeId, "call-2", SandboxIds.WRITE,
                "{\"path\":\"/workspace/plan.md\",\"content\":\"");

        ProcessingStep step = session.snapshot().stream()
                .filter(s -> s.id().startsWith("tool-sandbox__write"))
                .findFirst()
                .orElseThrow();
        assertThat(step.summary().active()).contains("plan.md").doesNotContain("…");
    }

    @Test
    void delta_fragmentPlaceholderName_stillUpdatesPath() {
        support.onToolCallStart(bridgeId, "call-4", SandboxIds.WRITE);
        // AgentScope 后续片 name 为占位，非 sandbox__write
        support.onToolCallDelta(bridgeId, "call-4", "__FRAGMENT__",
                "{\"path\":\"/workspace/from-frag.md\",\"content\":\"");

        ProcessingStep step = session.snapshot().stream()
                .filter(s -> s.id().startsWith("tool-sandbox__write"))
                .findFirst()
                .orElseThrow();
        assertThat(step.summary().active()).contains("from-frag.md");
    }

    @Test
    void start_ignoresNonWriteEditTools() {
        support.onToolCallStart(bridgeId, "call-3", SandboxIds.READ);
        assertThat(session.snapshot()).isEmpty();
    }
}
