package com.sunshine.tools.sdk.registry;

import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolSchemaGenerator {
    private ToolSchemaGenerator() {
    }

    public static List<RegisteredToolMethod> scan(Class<?> clazz) {
        List<RegisteredToolMethod> tools = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            SunshineTool annotation = method.getAnnotation(SunshineTool.class);
            if (annotation != null) {
                tools.add(fromMethod(method, null, annotation));
            }
        }
        return tools;
    }

    public static RegisteredToolMethod fromMethod(Method method, Object targetBean) {
        SunshineTool annotation = method.getAnnotation(SunshineTool.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Method is not annotated with @SunshineTool: " + method);
        }
        return fromMethod(method, targetBean, annotation);
    }

    private static RegisteredToolMethod fromMethod(Method method, Object targetBean, SunshineTool annotation) {
        return new RegisteredToolMethod(
                annotation.id(),
                annotation.displayName(),
                annotation.description(),
                annotation.sideEffect(),
                annotation.timelinePhase(),
                annotation.outputSummaryKind(),
                buildParametersSchema(method),
                method,
                targetBean);
    }

    private static Map<String, Object> buildParametersSchema(Method method) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
            String name = toolParam != null ? toolParam.value() : parameter.getName();
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", "string");
            if (toolParam != null && !toolParam.description().isBlank()) {
                property.put("description", toolParam.description());
            }
            properties.put(name, property);
            if (toolParam == null || toolParam.required()) {
                required.add(name);
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}
