package com.sunshine.bff.service;

import com.sunshine.bff.client.AuthCenterClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 为 Skill API 响应补充维护人展示名（DB 存 userId） */
@Service
@RequiredArgsConstructor
public class SkillMaintainerEnricher {

    private final AuthCenterClient authCenterClient;

    public Mono<Map<String, Object>> enrich(Map<String, Object> response) {
        Object data = response.get("data");
        if (data == null) {
            return Mono.just(response);
        }
        Set<String> userIds = new LinkedHashSet<>();
        collectUserIds(data, userIds);
        if (userIds.isEmpty()) {
            return Mono.just(response);
        }
        return authCenterClient.lookupDisplayNames(userIds)
                .map(names -> replaceData(response, applyNames(data, names)));
    }

    @SuppressWarnings("unchecked")
    private static void collectUserIds(Object data, Set<String> userIds) {
        if (data instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    collectFromMap((Map<String, Object>) map, userIds);
                }
            }
            return;
        }
        if (data instanceof Map<?, ?> map) {
            collectFromMap((Map<String, Object>) map, userIds);
        }
    }

    private static void collectFromMap(Map<String, Object> row, Set<String> userIds) {
        addUserId(row.get("activeVersionMaintainer"), userIds);
        if (row.containsKey("skillId")) {
            addUserId(row.get("maintainer"), userIds);
        }
    }

    private static void addUserId(Object raw, Set<String> userIds) {
        if (raw instanceof String id && StringUtils.hasText(id)) {
            userIds.add(id.trim());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object applyNames(Object data, Map<String, String> names) {
        if (data instanceof List<?> list) {
            List<Map<String, Object>> enriched = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    enriched.add(enrichRow((Map<String, Object>) map, names));
                }
            }
            return enriched;
        }
        if (data instanceof Map<?, ?> map) {
            return enrichRow((Map<String, Object>) map, names);
        }
        return data;
    }

    private static Map<String, Object> enrichRow(Map<String, Object> row, Map<String, String> names) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        if (copy.containsKey("activeVersionMaintainer")) {
            copy.put("activeVersionMaintainerName", resolveName(copy.get("activeVersionMaintainer"), names));
        }
        if (copy.containsKey("skillId") && copy.containsKey("maintainer")) {
            copy.put("maintainerName", resolveName(copy.get("maintainer"), names));
        }
        return copy;
    }

    private static String resolveName(Object raw, Map<String, String> names) {
        if (!(raw instanceof String id) || !StringUtils.hasText(id)) {
            return null;
        }
        return names.get(id.trim());
    }

    private static Map<String, Object> replaceData(Map<String, Object> response, Object data) {
        Map<String, Object> copy = new LinkedHashMap<>(response);
        copy.put("data", data);
        return copy;
    }
}
