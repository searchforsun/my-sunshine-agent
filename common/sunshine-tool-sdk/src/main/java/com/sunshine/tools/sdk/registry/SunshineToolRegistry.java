package com.sunshine.tools.sdk.registry;

import com.sunshine.tools.sdk.annotation.ToolParam;
import com.sunshine.tools.sdk.config.SunshineToolProperties;
import com.sunshine.tools.sdk.dto.SdkToolCatalogResponse;
import com.sunshine.tools.sdk.dto.SdkToolInvokeResponse;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SunshineToolRegistry implements SmartInitializingSingleton {
    private final ApplicationContext applicationContext;
    private final SunshineToolProperties properties;
    private final Environment environment;
    private final Map<String, RegisteredToolMethod> tools = new LinkedHashMap<>();

    public SunshineToolRegistry(ApplicationContext applicationContext,
                                SunshineToolProperties properties,
                                Environment environment) {
        this.applicationContext = applicationContext;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            if (bean instanceof SunshineToolRegistry) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(com.sunshine.tools.sdk.annotation.SunshineTool.class)) {
                    RegisteredToolMethod registered = ToolSchemaGenerator.fromMethod(method, bean);
                    tools.put(registered.id(), registered);
                }
            }
        }
    }

    public SdkToolCatalogResponse catalog() {
        List<SdkToolCatalogResponse.ToolEntry> entries = new ArrayList<>();
        for (RegisteredToolMethod tool : tools.values()) {
            entries.add(new SdkToolCatalogResponse.ToolEntry(
                    tool.id(),
                    tool.displayName(),
                    tool.description(),
                    tool.sideEffect(),
                    tool.timelineSummaryTemplate(),
                    tool.timelineSummaryExtract(),
                    tool.parametersSchema()));
        }
        return new SdkToolCatalogResponse(resolveAppId(), properties.getAppVersion(), properties.getSchemaVersion(), entries);
    }

    public SdkToolInvokeResponse invoke(String toolId, Map<String, String> params) {
        RegisteredToolMethod tool = tools.get(toolId);
        if (tool == null) {
            return SdkToolInvokeResponse.failure("Unknown tool: " + toolId);
        }
        try {
            Method method = tool.method();
            method.setAccessible(true);
            Object result = method.invoke(tool.targetBean(), buildArgs(method, params));
            return SdkToolInvokeResponse.success(result != null ? result.toString() : "");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return SdkToolInvokeResponse.failure(cause.getMessage());
        }
    }

    private Object[] buildArgs(Method method, Map<String, String> params) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            ToolParam toolParam = parameters[i].getAnnotation(ToolParam.class);
            String name = toolParam != null ? toolParam.value() : parameters[i].getName();
            args[i] = params != null ? params.get(name) : null;
        }
        return args;
    }

    private String resolveAppId() {
        if (StringUtils.hasText(properties.getAppId())) {
            return properties.getAppId();
        }
        return environment.getProperty("spring.application.name", "unknown");
    }
}
