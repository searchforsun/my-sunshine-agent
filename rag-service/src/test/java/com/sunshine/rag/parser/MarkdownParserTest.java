package com.sunshine.rag.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownParserTest {

    private MarkdownParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarkdownParser();
    }

    @Test
    @DisplayName("表格保持完整，不在行中间切开")
    void keepsTablesIntact() {
        String markdown = """
                ## 9. 附件清单

                | 假期类型 | 常见附件 |
                |----------|----------|
                | 病假 | 诊断证明、缴费凭证、病假条 |
                | 婚假 | 结婚证 |
                """;

        List<String> chunks = parser.parse(markdown);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anyMatch(chunk ->
                chunk.contains("| 假期类型 | 常见附件 |")
                        && chunk.contains("| 病假 | 诊断证明、缴费凭证、病假条 |")
                        && chunk.contains("| 婚假 | 结婚证 |"));
        assertThat(chunks).noneMatch(chunk -> chunk.trim().startsWith("| 婚假 |"));
        assertThat(chunks).noneMatch(chunk -> chunk.trim().startsWith("诊断证明"));
    }

    @Test
    @DisplayName("不在句子中间产生 overlap 残片")
    void doesNotStartWithSentenceFragment() throws IOException {
        String markdown = readSample("leave-process-sample.md");
        List<String> chunks = parser.parse(markdown);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).noneMatch(chunk -> chunk.trim().startsWith("及公司制度执行）"));
        assertThat(chunks).noneMatch(chunk -> chunk.trim().startsWith("吗？**"));
        assertThat(chunks).noneMatch(chunk -> chunk.trim().startsWith("[填写系统地址]"));
    }

    @Test
    @DisplayName("FAQ 条目不被从问题中间切开")
    void keepsFaqEntriesReadable() throws IOException {
        String markdown = readSample("leave-process-sample.md");
        List<String> chunks = parser.parse(markdown);

        assertThat(chunks).anyMatch(chunk ->
                chunk.contains("**Q4：远程办公算请假吗？**")
                        && chunk.contains("**Q5：审批被驳回怎么办？**"));
    }

    @Test
    @DisplayName("chunk 带有章节标题上下文")
    void includesSectionBreadcrumb() throws IOException {
        String markdown = readSample("leave-process-sample.md");
        List<String> chunks = parser.parse(markdown);

        assertThat(chunks).anyMatch(chunk ->
                chunk.contains("## 9. 附件清单") && chunk.contains("| 病假 | 诊断证明"));
        assertThat(chunks).anyMatch(chunk ->
                chunk.contains("## 11. 相关制度与联系方式")
                        && chunk.contains("| OA 系统 | https://oa.example.com/leave |"));
    }

    @Test
    @DisplayName("二级标题边界优先分块")
    void splitsAtMajorSectionBoundaries() throws IOException {
        String markdown = readSample("leave-process-sample.md");
        List<String> chunks = parser.parse(markdown);

        assertThat(chunks.size()).isGreaterThan(2);
        long attachmentSections = chunks.stream()
                .filter(chunk -> chunk.contains("## 9. 附件清单"))
                .count();
        long faqSections = chunks.stream()
                .filter(chunk -> chunk.contains("## 10. 常见问题 FAQ"))
                .count();
        assertThat(attachmentSections).isGreaterThanOrEqualTo(1);
        assertThat(faqSections).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("代码块作为整体保留")
    void keepsCodeFenceIntact() {
        String markdown = """
                ## 12. 附录

                ```markdown
                ### 请假申请单
                - **姓名**：
                - **部门**：
                ```
                """;

        List<String> chunks = parser.parse(markdown);

        assertThat(chunks).anyMatch(chunk ->
                chunk.contains("```markdown")
                        && chunk.contains("### 请假申请单")
                        && chunk.contains("- **姓名**："));
    }

    private static String readSample(String resourceName) throws IOException {
        try (var in = MarkdownParserTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
