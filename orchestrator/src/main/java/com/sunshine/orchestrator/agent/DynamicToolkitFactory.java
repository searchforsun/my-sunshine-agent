package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 Nacos react 工具白名单动态组装 Toolkit
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicToolkitFactory {

    private final RagTool ragTool;
    private final ToolManagerClient toolManagerClient;
    private final AgentExecutionProperties executionProperties;

    public Toolkit build() {
        Toolkit tk = new Toolkit();
        List<String> registered = new ArrayList<>();
        List<String> whitelist = executionProperties.getReact() != null
                ? executionProperties.getReact().getTools()
                : List.of();

        for (String toolName : whitelist) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            switch (toolName) {
                case "search_knowledge" -> {
                    tk.registerTool(ragTool);
                    registered.add(toolName);
                }
                case "list_finance_messages" -> {
                    tk.registerTool(new RemoteToolProxy(toolManagerClient));
                    registered.add(toolName);
                }
                default -> log.warn("[Orchestrator] react 白名单含未知工具，已跳过: {}", toolName);
            }
        }

        log.info("[Orchestrator] DynamicToolkit 已注册工具: {}", String.join(", ", registered));
        return tk;
    }
}
