package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.common.sandbox.SandboxEditDiffLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Feign/WebClient 可能将 meta.editDiff 反序列化为 Map，统一解析为 record */
public final class SandboxEditDiffCodec {

    private SandboxEditDiffCodec() {}

    public static SandboxEditDiff fromMeta(Object raw) {
        if (raw instanceof SandboxEditDiff diff) {
            return diff;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String path = stringValue(map.get("path"));
        int contextRadius = 0;
        if (map.get("contextRadius") instanceof Number radius) {
            contextRadius = radius.intValue();
        }
        Object linesObj = map.get("lines");
        if (!(linesObj instanceof List<?> lineRows) || lineRows.isEmpty()) {
            return null;
        }
        List<SandboxEditDiffLine> lines = new ArrayList<>();
        for (Object rowObj : lineRows) {
            if (!(rowObj instanceof Map<?, ?> row)) {
                continue;
            }
            String kind = stringValue(row.get("kind"));
            if (kind == null) {
                continue;
            }
            String text = row.get("text") != null ? String.valueOf(row.get("text")) : "";
            Integer oldLine = row.get("oldLine") instanceof Number n ? n.intValue() : null;
            Integer newLine = row.get("newLine") instanceof Number n ? n.intValue() : null;
            lines.add(new SandboxEditDiffLine(kind, text, oldLine, newLine));
        }
        if (lines.isEmpty()) {
            return null;
        }
        return new SandboxEditDiff(path, contextRadius, List.copyOf(lines));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
