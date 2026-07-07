package com.sunshine.tool.tool;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.exception.ToolErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * search_knowledge — 仅 catalog 元数据；执行由 orchestrator {@code RagTool} 本地完成。
 */
@Component
public class SearchKnowledgeToolHandler implements ToolHandler {

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String displayName() {
        return "检索知识库";
    }

    @Override
    public String description() {
        return "搜索企业知识库获取相关文档。当用户询问专业知识、公司政策、技术规范、操作手册等问题时优先调用。";
    }

    @Override
    public String kind() {
        return "local";
    }

    @Override
    public String timelinePhase() {
        return "rag";
    }

    @Override
    public String outputSummaryKind() {
        return "hit-count";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return ToolParamSchemas.stringParam("query", "自然语言查询文本，将用于向量检索匹配相关文档片段");
    }

    @Override
    public String invoke(Map<String, String> params) {
        throw new BizException(ToolErrorCode.LOCAL_TOOL_INVOKE);
    }
}
