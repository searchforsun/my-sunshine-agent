package com.sunshine.tool.summary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTimelineSummaryEngineTest {

    private final ToolTimelineSummaryEngine engine = new ToolTimelineSummaryEngine();

    @Test
    void emptyTemplate_returnsEmpty() {
        assertThat(engine.resolve("", null, "any", 80)).isEmpty();
    }

    @Test
    void financeSummary_regexExtract() {
        String template = "{status} {count} 条，合计 ¥{amount}";
        String extract = """
                {"status":"regex:status=([^|\\\\s]+)","count":"regex:count=(\\\\d+)","amount":"regex:totalAmount=([\\\\d.]+)"}""";
        String raw = """
                财务消息汇总：
                - status=pending | count=3 | totalAmount=124140.50""";
        assertThat(engine.resolve(template, extract, raw, 80))
                .isEqualTo("pending 3 条，合计 ¥124140.50");
    }

    @Test
    void outputBuiltin_firstLine() {
        assertThat(engine.resolve("{output}", null, "已审批待办 1004（模拟写操作）", 80))
                .isEqualTo("已审批待办 1004（模拟写操作）");
    }
}
