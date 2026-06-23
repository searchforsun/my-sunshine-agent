package com.sunshine.skill.skillmd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillMdParserTest {

    private static final String SAMPLE = """
            ---
            name: finance-analysis
            description: 待审批单据分析
            ---

            # 财务合规分析

            你是财务合规分析子 Agent。
            """;

    @Test
    void parse_extractsFrontmatterAndBody() {
        SkillMdDocument doc = SkillMdParser.parse(SAMPLE);
        assertThat(doc.name()).isEqualTo("finance-analysis");
        assertThat(doc.description()).isEqualTo("待审批单据分析");
        assertThat(doc.body()).startsWith("# 财务合规分析");
        assertThat(doc.body()).doesNotContain("name:");
    }

    @Test
    void parse_plainMarkdownWithoutFrontmatter() {
        SkillMdDocument doc = SkillMdParser.parse("# 仅正文\n\n说明");
        assertThat(doc.name()).isEmpty();
        assertThat(doc.body()).isEqualTo("# 仅正文\n\n说明");
    }

    @Test
    void parse_ignoresNonStandardFrontmatterFields() {
        String raw = """
                ---
                name: finance-analysis
                description: 描述
                tools:
                  - list_finance_messages
                max-iters: 4
                ---

                # 正文
                """;
        SkillMdDocument doc = SkillMdParser.parse(raw);
        assertThat(doc.name()).isEqualTo("finance-analysis");
        assertThat(doc.description()).isEqualTo("描述");
        assertThat(doc.body()).startsWith("# 正文");
    }

    @Test
    void parse_rejectsEmptyBody() {
        assertThatThrownBy(() -> SkillMdParser.parse("---\nname: x\n---\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
