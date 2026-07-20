package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxTimelineLabelServiceTest {

    private SandboxTimelineLabelService labels;

    @BeforeEach
    void setUp() {
        labels = new SandboxTimelineLabelService(TimelinePromptCatalog.withDefaults());
    }

    @Test
    void after_readWriteEdit_useHeaderPath() {
        assertThat(labels.after(SandboxIds.READ, "读文件", Map.of("path", "/skills/demo/scripts/hello.py")))
                .isEqualTo("hello.py");
        assertThat(labels.after(SandboxIds.WRITE, "写文件", Map.of("path", "/workspace/test.txt")))
                .isEqualTo("test.txt");
        assertThat(labels.after(SandboxIds.EDIT, "编辑文件", Map.of("path", "/workspace/a.py")))
                .isEqualTo("a.py");
    }

    @Test
    void after_glob_infersSearchRootFromResults() {
        String raw = "/skills/sandbox-coding-demo/SKILL.md\n/skills/sandbox-coding-demo/scripts/hello.py\n";
        Map<String, Object> enriched = SandboxStepContext.enrichInput(
                SandboxIds.GLOB, Map.of("pattern", "**/*"), raw);
        assertThat(labels.after(SandboxIds.GLOB, "查找文件", enriched))
                .isEqualTo("**/* · /skills");
    }

    @Test
    void after_globGrep_includePatternAndOptionalPath() {
        assertThat(labels.after(SandboxIds.GLOB, "查找文件", Map.of("pattern", "**/*.py")))
                .isEqualTo("**/*.py");
        assertThat(labels.after(SandboxIds.GLOB, "查找文件",
                Map.of("pattern", "**/*.py", "path", "/skills/demo")))
                .isEqualTo("**/*.py · /skills/demo");
        assertThat(labels.after(SandboxIds.GLOB, "查找文件",
                Map.of("pattern", "**/*", "path", "/skills")))
                .isEqualTo("**/* · /skills");
        assertThat(labels.after(SandboxIds.GREP, "搜索内容",
                Map.of("pattern", "Hello", "path", "/skills/demo/scripts")))
                .isEqualTo("Hello");
    }

    @Test
    void after_exec_includeCommand() {
        assertThat(labels.after(SandboxIds.EXEC, "执行命令", Map.of("command", "ls /workspace")))
                .isEqualTo("ls /workspace");
    }

    @Test
    void after_exec_keepsFullLongCommand() {
        String cmd = "python3 -c \"import csv; total=0.0; "
                + "x".repeat(120)
                + "\"";
        assertThat(labels.after(SandboxIds.EXEC, "执行命令", Map.of("command", cmd)))
                .isEqualTo(cmd);
    }

    @Test
    void after_missingParams_fallsBackEmpty() {
        assertThat(labels.after(SandboxIds.READ, "读文件", Map.of()))
                .isEqualTo("");
    }

    @Test
    void active_includesTarget() {
        assertThat(labels.active(SandboxIds.READ, "读文件", Map.of("path", "/skills/demo/a.py")))
                .isEqualTo("正在读取 /skills/demo/a.py");
        assertThat(labels.active(SandboxIds.EXEC, "执行命令", Map.of("command", "pwd")))
                .isEqualTo("正在执行 pwd");
    }

    @Test
    void headerPath_and_inferSearchRoot() {
        assertThat(SandboxTimelineLabelService.headerPath("/skills/demo/scripts/hello.py"))
                .isEqualTo("hello.py");
        assertThat(SandboxTimelineLabelService.headerPath("/skills"))
                .isEqualTo("/skills");
        assertThat(SandboxTimelineLabelService.inferSearchRootFromPaths(
                "/skills/a.md\n/skills/b.py\n")).isEqualTo("/skills");
    }

    @Test
    void fileName_extractsBasename() {
        assertThat(SandboxTimelineLabelService.fileName("/skills/demo/scripts/hello.py"))
                .isEqualTo("hello.py");
    }
}
