package com.sunshine.skill.skillmd;

import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cursor 兼容 SKILL.md 解析 — name / description / tools + Markdown 正文 */
public final class SkillMdParser {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\R(.*?\\R)---\\s*\\R?(.*)\\z",
            Pattern.DOTALL);

    private SkillMdParser() {
    }

    public static SkillMdDocument parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("SKILL.md 内容为空");
        }
        String text = raw.replace("\r\n", "\n");
        Matcher matcher = FRONTMATTER.matcher(text);
        Map<String, Object> meta;
        String body;
        if (matcher.matches()) {
            meta = loadYaml(matcher.group(1));
            body = matcher.group(2).strip();
        } else {
            meta = Map.of();
            body = text.strip();
        }
        if (!StringUtils.hasText(body)) {
            throw new IllegalArgumentException("SKILL.md 正文为空");
        }
        return new SkillMdDocument(
                text(meta, "name"),
                text(meta, "description"),
                stringList(meta, "tools"),
                body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String yamlBlock) {
        Object loaded = new Yaml().load(yamlBlock);
        if (loaded instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            return normalized;
        }
        return Map.of();
    }

    private static String text(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value != null ? String.valueOf(value).strip() : "";
    }

    /** frontmatter tools：YAML 数组或逗号分隔字符串，逐项 trim */
    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    out.add(String.valueOf(item).strip());
                }
            }
            return List.copyOf(out);
        }
        String raw = String.valueOf(value);
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                out.add(part.strip());
            }
        }
        return List.copyOf(out);
    }
}
