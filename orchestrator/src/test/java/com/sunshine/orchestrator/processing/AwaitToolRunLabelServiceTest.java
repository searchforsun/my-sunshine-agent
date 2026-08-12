package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(TimelineLabelJUnitExtension.class)
class AwaitToolRunLabelServiceTest {

    @Test
    void defaults_hideRawToolIds() {
        assertThat(AwaitToolRunLabels.label()).isEqualTo("等待结果");
        assertThat(AwaitToolRunLabels.active()).isEqualTo("正在等待后台任务");
        assertThat(AwaitToolRunLabels.after()).isEqualTo("等待完成");
        assertThat(AwaitToolRunLabels.backgroundExecLabel()).isEqualTo("后台执行");
        assertThat(AwaitToolRunLabels.label()).doesNotContain("await_tool_run");
        assertThat(AwaitToolRunLabels.backgroundExecLabel()).doesNotContain("sandbox__");
    }

    @Test
    void service_readsDefaultsWhenCatalogMissingEntry() {
        AwaitToolRunLabelService service = new AwaitToolRunLabelService(TimelinePromptCatalog.withDefaults());
        assertThat(service.label()).isEqualTo("等待结果");
        assertThat(service.backgroundExecLabel()).isEqualTo("后台执行");
    }
}
