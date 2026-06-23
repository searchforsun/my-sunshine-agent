package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 复现 live flash 模型典型输出与解析/校验行为 */
class PlanJsonParserLiveSampleTest {

    private final PlanJsonParser parser = new PlanJsonParser();

    @Test
    void parsesLiveFlashShapeWithConfigAlias() throws Exception {
        String raw = Files.readString(Path.of("src/test/resources/planner-live-sample.json"));
        PlanJson plan = parser.parse(raw);
        assertThat(plan.nodes()).hasSize(4);
        assertThat(plan.nodes().stream().filter(n -> "rag".equals(n.type())).findFirst())
                .get()
                .satisfies(n -> assertThat(n.params()).containsEntry("query", "差旅报销制度"));
    }

    @Test
    void extractsJsonEmbeddedInText() {
        String wrapped = "思考完毕。\n{\"planId\":null,\"reason\":\"r\",\"nodes\":[{\"id\":\"n1\",\"type\":\"rag\",\"params\":{}},{\"id\":\"n2\",\"type\":\"answer\",\"params\":{}}],\"edges\":[{\"from\":\"start\",\"to\":\"n1\"},{\"from\":\"n1\",\"to\":\"n2\"}]}\n";
        PlanJson plan = parser.parse(wrapped);
        assertThat(plan.nodes()).hasSize(2);
    }
}
