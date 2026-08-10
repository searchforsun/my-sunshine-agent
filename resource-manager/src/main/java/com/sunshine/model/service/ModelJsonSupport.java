package com.sunshine.model.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.model.dto.ModelCapabilities;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;

final class ModelJsonSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ModelJsonSupport() {
    }

    static String normalizeTenantId(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
    }

    static String writeCapabilities(ModelCapabilities capabilities) {
        ModelCapabilities value = capabilities != null ? capabilities : ModelCapabilities.defaults();
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("serialize capabilities failed", e);
        }
    }

    static ModelCapabilities readCapabilities(String json) {
        if (!StringUtils.hasText(json)) {
            return ModelCapabilities.defaults();
        }
        try {
            return MAPPER.readValue(json, ModelCapabilities.class);
        } catch (Exception e) {
            throw new IllegalStateException("parse capabilities failed", e);
        }
    }

    static String writeExtras(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(extras);
        } catch (Exception e) {
            throw new IllegalStateException("serialize extras failed", e);
        }
    }

    static Map<String, Object> readExtras(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            Map<String, Object> map = MAPPER.readValue(json, MAP_TYPE);
            return map == null || map.isEmpty() ? null : Collections.unmodifiableMap(map);
        } catch (Exception e) {
            throw new IllegalStateException("parse extras failed", e);
        }
    }
}
