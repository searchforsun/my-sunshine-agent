package com.sunshine.orchestrator.hitl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HitlParamSupportTest {

    @Test
    void summarizeParams_omitsBodyKeys() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("path", "/workspace/sample.csv");
        params.put("content", "id,amount\nA001,150.50");
        assertThat(HitlParamSupport.summarizeParams(params)).isEqualTo("path=/workspace/sample.csv");
    }

    @Test
    void expandBodyFromParams_writeContent() {
        assertThat(HitlParamSupport.expandBodyFromParams(Map.of(
                "path", "/workspace/a.txt",
                "content", "hello")))
                .isEqualTo("hello");
    }

    @Test
    void expandBodyFromParams_editOldNew() {
        assertThat(HitlParamSupport.expandBodyFromParams(Map.of(
                "path", "/workspace/a.txt",
                "old_string", "a\nb\nc",
                "new_string", "a\nx\nc")))
                .isNull();
    }

    @Test
    void summarizeParams_omitsCommand() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("command", "python3 -c \"print(1)\"");
        params.put("cwd", "/workspace");
        assertThat(HitlParamSupport.summarizeParams(params)).isEqualTo("cwd=/workspace");
    }

    @Test
    void expandBodyFromParams_execCommand() {
        assertThat(HitlParamSupport.expandBodyFromParams(Map.of(
                "command", "ls -la")))
                .isEqualTo("ls -la");
    }
}
