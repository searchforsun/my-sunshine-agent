package com.sunshine.rag.admin.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/** SnakeYAML patch：按 dotted path 合并 camelCase → kebab-case 字段 */
final class NacosYamlPatcher {

    private NacosYamlPatcher() {
    }

    static String patch(String yamlContent, String nacosPath, Map<String, Object> payload) {
        Map<String, Object> root = loadRoot(yamlContent);
        Map<String, Object> target = navigate(root, nacosPath, true);
        if (payload != null) {
            payload.forEach((key, value) -> target.put(camelToKebab(key), value));
        }
        return dump(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadRoot(String content) {
        if (content == null || content.isBlank()) {
            return new LinkedHashMap<>();
        }
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(content);
        if (loaded instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        throw new IllegalStateException("Nacos 配置不是 YAML map");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigate(Map<String, Object> root, String path, boolean create) {
        String[] parts = path.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = node.get(part);
            if (!(next instanceof Map<?, ?> nextMap)) {
                if (!create) {
                    throw new IllegalStateException("Nacos path 不存在: " + path);
                }
                LinkedHashMap<String, Object> created = new LinkedHashMap<>();
                node.put(part, created);
                node = created;
            } else {
                node = (Map<String, Object>) nextMap;
            }
        }
        String leaf = parts[parts.length - 1];
        Object target = node.get(leaf);
        if (!(target instanceof Map<?, ?> targetMap)) {
            if (!create) {
                throw new IllegalStateException("Nacos path 不存在: " + path);
            }
            LinkedHashMap<String, Object> created = new LinkedHashMap<>();
            node.put(leaf, created);
            return created;
        }
        return (Map<String, Object>) targetMap;
    }

    private static String dump(Map<String, Object> root) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options).dump(root);
    }

    static String camelToKebab(String key) {
        if (key == null || key.isBlank()) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length() + 4);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('-');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
