package com.sunshine.orchestrator.execution;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按节点 type 路由至对应 NodeHandler
 */
@Component
public class NodeHandlerRegistry {

    private final Map<String, NodeHandler> handlers;

    public NodeHandlerRegistry(List<NodeHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(NodeHandler::type, Function.identity(), (a, b) -> a));
    }

    public NodeHandler require(String type) {
        NodeHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("未知节点类型: " + type);
        }
        return handler;
    }
}
