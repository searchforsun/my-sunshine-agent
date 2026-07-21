package com.sunshine.tools.sdk.registry;

import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemaGeneratorTest {

    @Component
    static class SampleTools {
        @SunshineTool(
                id = "list_my_expenses",
                displayName = "查询待审批财务消息",
                description = "按状态筛选",
                timelineSummaryTemplate = "{count} 条财务消息",
                timelineSummaryExtract = "{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}")
        public String list(@ToolParam(value = "status", description = "pending|approved|all") String status) {
            return "ok";
        }
    }

    @Test
    void generatesOpenAiParametersSchema() {
        List<RegisteredToolMethod> tools = ToolSchemaGenerator.scan(SampleTools.class);
        assertThat(tools).hasSize(1);
        RegisteredToolMethod t = tools.get(0);
        assertThat(t.id()).isEqualTo("list_my_expenses");
        assertThat(t.timelineSummaryTemplate()).isEqualTo("{count} 条财务消息");
        Map<String, Object> schema = t.parametersSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("status");
    }
}
