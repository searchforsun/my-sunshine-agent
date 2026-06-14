package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.client.RagContextFormatter;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具 — 注册到 AgentScope Toolkit
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagTool {

    private final RagClient ragClient;

    @Tool(name = "search_knowledge",
            description = "搜索企业知识库获取相关文档。当用户询问专业知识、公司政策、"
                    + "技术规范、操作手册等需要参考文档的问题时，应优先调用此工具进行检索。")
    public String searchKnowledge(
            @ToolParam(name = "query", description = "自然语言查询文本，将用于向量检索匹配相关文档片段")
            String query) {

        log.info("[RagTool] Agent 调用知识库检索: query='{}'",
                query != null && query.length() > 50
                        ? query.substring(0, 50) + "..."
                        : query);

        var results = ragClient.search(query, 3).block();
        return RagContextFormatter.formatToolResult(results);
    }
}
