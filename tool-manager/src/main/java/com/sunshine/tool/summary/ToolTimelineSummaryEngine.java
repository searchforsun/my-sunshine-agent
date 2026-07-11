package com.sunshine.tool.summary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具时间线一步摘要 — 按 catalog 配置的模板 + 提取表达式解析，无内置业务 kind。
 * <p>表达式：{@code regex:<pattern>} 取第 1 捕获组；{@code json:<path>} 点路径；{@code line:<n>} 行号（0 起）。
 * 模板未配置则返回空串（时间线走 orchestrator Nacos steps.tool 默认 after）。
 */
@Component
public class ToolTimelineSummaryEngine {

    private static final ObjectMapper OM = new ObjectMapper();

    public String resolve(String template, String extractJson, String rawText, int truncateMaxChars) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        String text = rawText != null ? rawText : "";
        Map<String, String> bindings = parseBindings(extractJson);
        Map<String, String> vars = new LinkedHashMap<>();
        if (template.contains("{output}")) {
            vars.put("output", truncateFirstLine(text, truncateMaxChars));
        }
        bindings.forEach((name, expr) -> vars.put(name, eval(expr, text)));
        return applyTemplate(template.strip(), vars);
    }

    private Map<String, String> parseBindings(String extractJson) {
        if (!StringUtils.hasText(extractJson)) {
            return Map.of();
        }
        try {
            Map<String, String> map = OM.readValue(extractJson.strip(), new TypeReference<>() {});
            return map != null ? map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String eval(String expression, String text) {
        if (!StringUtils.hasText(expression)) {
            return "";
        }
        String expr = expression.strip();
        if (expr.startsWith("regex:")) {
            return evalRegex(expr.substring(6).strip(), text);
        }
        if (expr.startsWith("json:")) {
            return evalJson(expr.substring(5).strip(), text);
        }
        if (expr.startsWith("line:")) {
            return evalLine(expr.substring(5).strip(), text);
        }
        return "";
    }

    private String evalRegex(String pattern, String text) {
        try {
            Matcher matcher = Pattern.compile(pattern, Pattern.DOTALL).matcher(text);
            return matcher.find() ? matcher.group(1).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String evalJson(String path, String text) {
        try {
            JsonNode root = OM.readTree(text);
            JsonNode node = root.at(toJsonPointer(path));
            if (node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.isValueNode() ? node.asText().strip() : node.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String evalLine(String indexRaw, String text) {
        try {
            int index = Integer.parseInt(indexRaw.trim());
            String[] lines = text.split("\\R");
            if (index < 0 || index >= lines.length) {
                return "";
            }
            return lines[index].strip();
        } catch (Exception e) {
            return "";
        }
    }

    private static String toJsonPointer(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String normalized = path.strip()
                .replace("[", "/")
                .replace("]", "")
                .replace(".", "/");
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private String applyTemplate(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return result.strip();
    }

    private static String truncateFirstLine(String text, int max) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("").strip();
        if (max > 0 && line.length() > max) {
            return line.substring(0, max) + "…";
        }
        return line;
    }
}
