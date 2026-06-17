package com.sunshine.tool.service;

import com.sunshine.tool.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ToolInvokeService {

    private final ToolRegistry toolRegistry;

    public String invoke(String name, Map<String, String> params) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name required");
        }
        return toolRegistry.invoke(name, params);
    }
}
