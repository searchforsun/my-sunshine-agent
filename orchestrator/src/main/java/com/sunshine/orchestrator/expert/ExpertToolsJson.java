package com.sunshine.orchestrator.expert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 专家 tools_json 解析；`["*"]` 为过渡全量哨兵。 */
public final class ExpertToolsJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_STRING = new TypeReference<>() {};

    private ExpertToolsJson() {}

    public static List<String> parse(String toolsJson) {
        if (!StringUtils.hasText(toolsJson)) {
            return List.of();
        }
        try {
            List<String> raw = MAPPER.readValue(toolsJson.strip(), LIST_STRING);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String id : raw) {
                if (StringUtils.hasText(id)) {
                    out.add(id.strip());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 过渡：仅当解析结果恰好为单个 `"*"` */
    public static boolean isStarAll(List<String> toolIds) {
        return toolIds != null && toolIds.size() == 1 && "*".equals(toolIds.get(0));
    }
}
