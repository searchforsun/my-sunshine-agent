package com.sunshine.rag.admin.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** bundle payload 点路径读写（search.minScore / rewrite.rag.systemPrompt） */
public final class ConfigBundlePathUtils {

    private ConfigBundlePathUtils() {
    }

    @SuppressWarnings("unchecked")
    public static Object getPath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    public static void setPath(Map<String, Object> root, String path, Object value) {
        if (root == null || path == null || path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(parts[parts.length - 1], value);
    }
}
