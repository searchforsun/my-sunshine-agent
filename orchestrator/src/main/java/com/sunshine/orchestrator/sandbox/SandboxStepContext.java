package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.processing.StepMetadata;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** 沙箱工具步：glob 入参补全 + 结构化 metadata（前端勿再解析/推断主行摘要） */
public final class SandboxStepContext {

    private SandboxStepContext() {
    }

    public static Map<String, Object> enrichInput(String toolId, Map<String, Object> input, String toolResult) {
        Map<String, Object> base = input != null ? input : Map.of();
        if (!SandboxIds.GLOB.equals(toolId)) {
            return base;
        }
        if (StringUtils.hasText(str(base, "path")) || !StringUtils.hasText(toolResult)) {
            return base;
        }
        String root = SandboxTimelineLabelService.inferSearchRootFromPaths(toolResult);
        if (!StringUtils.hasText(root)) {
            return base;
        }
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("path", root);
        return out;
    }

    public static StepMetadata metadata(String toolId, Map<String, Object> input, String afterSummary) {
        if (!StringUtils.hasText(toolId)) {
            return null;
        }
        String path = str(input, "path");
        if (SandboxIds.READ.equals(toolId) || SandboxIds.WRITE.equals(toolId) || SandboxIds.EDIT.equals(toolId)) {
            return StringUtils.hasText(path) ? StepMetadata.fromSandbox(path.strip(), null) : null;
        }
        if (SandboxIds.GLOB.equals(toolId)) {
            String root = SandboxTimelineLabelService.extractSearchRootFromAfter(afterSummary);
            if (!StringUtils.hasText(root) && StringUtils.hasText(path)) {
                root = path.strip();
            }
            return StringUtils.hasText(root) ? StepMetadata.fromSandbox(null, root) : null;
        }
        return null;
    }

    private static String str(Map<String, Object> input, String key) {
        if (input == null || key == null) {
            return "";
        }
        Object v = input.get(key);
        return v != null ? String.valueOf(v).strip() : "";
    }
}
