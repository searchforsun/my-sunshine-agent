package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.RagClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具 — 注册到 AgentScope Toolkit
 * Agent 对话时自动判断是否需要调用此工具检索企业知识库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagTool {

    private final RagClient ragClient;

    /**
     * 搜索企业知识库
     * LLM 通过 @Tool 注解理解工具用途和参数
     *
     * @param query 自然语言查询
     * @return 检索到的文档片段，用分隔符拼接
     */
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

        List<String> results = ragClient.search(query, 3).block();

        if (results == null || results.isEmpty()) {
            return "未找到相关知识库内容。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("知识库检索结果（共 ").append(results.size()).append(" 条）：\n\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append("【文档片段 ").append(i + 1).append("】\n");
            sb.append(results.get(i)).append("\n\n");
        }
        sb.append("请基于以上知识库内容回答用户问题。");
        return sb.toString();
    }
}
