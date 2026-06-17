package com.sunshine.tool.registry;

import com.sunshine.tool.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具名 → ToolHandler 自动注册表，替代 switch 硬编码
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolHandler> handlers;

    public ToolRegistry(List<ToolHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ToolHandler::name, Function.identity(), (a, b) -> a));
    }

    public String invoke(String name, Map<String, String> params) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        return handler.invoke(params);
    }
}
