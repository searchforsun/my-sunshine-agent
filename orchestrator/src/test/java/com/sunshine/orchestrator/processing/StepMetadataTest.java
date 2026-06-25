package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepMetadataTest {

    @Test
    void fromRagToolOutput_rawText_extractsHitCountAndDedupedSources() {
        String raw = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                正文
                【公司请假流程规范 | 片段 2】
                正文
                """;
        StepMetadata metadata = StepMetadata.fromRagToolOutput(raw);

        assertThat(metadata.hitCount()).isEqualTo(3);
        assertThat(metadata.sources()).containsExactly("公司请假流程规范");
    }

    @Test
    void fromRagToolOutput_multipleDocs_dedupesAndJoins() {
        String raw = """
                知识库检索结果（共 2 条）：
                来源文档：制度A、制度B

                【制度A | 片段 1】
                x
                【制度B | 片段 1】
                y
                """;
        StepMetadata metadata = StepMetadata.fromRagToolOutput(raw);

        assertThat(metadata.hitCount()).isEqualTo(2);
        assertThat(metadata.sources()).containsExactly("制度A", "制度B");
        assertThat(metadata.sourcesLabel()).isEqualTo("制度A、制度B");
    }

    @Test
    void fromRagToolOutput_summarizedLine_parsesSources() {
        StepMetadata metadata = StepMetadata.fromRagToolOutput("命中 3 条，来源：公司请假流程规范");
        assertThat(metadata.hitCount()).isEqualTo(3);
        assertThat(metadata.sources()).containsExactly("公司请假流程规范");
    }

    @Test
    void fromRagToolOutput_oneLineBlob_doesNotSplitFragmentBodyBy顿号() {
        String raw = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                关键岗位、项目节点、值班期间请假，主管可要求调整时间。
                """;
        StepMetadata metadata = StepMetadata.fromRagToolOutput(raw);
        assertThat(metadata.hitCount()).isEqualTo(3);
        assertThat(metadata.sources()).containsExactly("公司请假流程规范");
    }

    @Test
    void isEmpty_falseWhenOnlyRoutingReason() {
        StepMetadata metadata = StepMetadata.fromRouting(
                new com.sunshine.orchestrator.routing.ExecutionPlan(
                        com.sunshine.orchestrator.routing.ExecutionMode.SIMPLE_LLM,
                        null,
                        java.util.Map.of(),
                        "user:forced-simple-llm"));
        assertThat(metadata).isNotNull();
        assertThat(metadata.isEmpty()).isFalse();
    }

    @Test
    void fromRagToolOutput_fragmentContainsWeiZhaoDao_stillCountsHits() {
        String raw = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                若未找到直属主管，请联系 HR。
                """;
        StepMetadata metadata = StepMetadata.fromRagToolOutput(raw);
        assertThat(metadata.hitCount()).isEqualTo(3);
        assertThat(metadata.sources()).containsExactly("公司请假流程规范");
    }
}
