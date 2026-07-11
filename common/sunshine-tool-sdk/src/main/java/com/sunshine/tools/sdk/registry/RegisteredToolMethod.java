package com.sunshine.tools.sdk.registry;

import java.lang.reflect.Method;
import java.util.Map;

public record RegisteredToolMethod(
        String id,
        String displayName,
        String description,
        String sideEffect,
        String timelinePhase,
        String timelineSummaryTemplate,
        String timelineSummaryExtract,
        Map<String, Object> parametersSchema,
        Method method,
        Object targetBean) {
}
