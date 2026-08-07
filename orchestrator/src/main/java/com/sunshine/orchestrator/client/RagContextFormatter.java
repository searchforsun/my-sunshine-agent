package com.sunshine.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 将 RAG 检索结果格式化为工具返回或 Workflow 上下文。
 * 格式文案 SSOT：prompt-manager Catalog id={@code rag.tool-result}（content_json），
 * 缺失 → 各字段按空串处理 + warn；占位符 {count}/{reason} 运行时替换。
 */
@Slf4j
@Component
public class RagContextFormatter {

    public static final String CATALOG_ID = "rag.tool-result";

    public enum Mode { TOOL, WORKFLOW }

    private final PromptCatalogHolder catalogHolder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagContextFormatter(PromptCatalogHolder catalogHolder) {
        this.catalogHolder = catalogHolder;
    }

    public String formatHits(List<RagClient.RagHit> hits, Mode mode) {
        JsonNode cfg = resolveConfig();
        if (hits == null || hits.isEmpty()) {
            return text(cfg, mode == Mode.TOOL ? "emptyTool" : "emptyWorkflow");
        }
        StringBuilder sb = new StringBuilder();
        String header = text(cfg, mode == Mode.TOOL ? "toolHeader" : "workflowHeader");
        if (StringUtils.hasText(header)) {
            sb.append(header.replace("{count}", String.valueOf(hits.size()))).append('\n');
        }
        appendHitsBody(sb, hits);
        if (mode == Mode.TOOL) {
            String cite = text(cfg, "citeRule");
            if (StringUtils.hasText(cite)) {
                sb.append(cite);
            }
        }
        return sb.toString().strip();
    }

    public String formatToolResult(List<RagClient.RagHit> hits) {
        return formatHits(hits, Mode.TOOL);
    }

    /** Workflow / 预检索上下文 */
    public String formatAgentContext(List<RagClient.RagHit> hits) {
        return formatHits(hits, Mode.WORKFLOW);
    }

    /** RAG 检索失败提示：Catalog 模板 {reason} 替换；缺失时仅保留「工具调用失败：{reason}」事实，不注入指令 */
    public String formatError(String reason) {
        String template = text(resolveConfig(), "errorHint");
        if (!StringUtils.hasText(template)) {
            return "工具调用失败：" + reason;
        }
        return template.replace("{reason}", reason);
    }

    private JsonNode resolveConfig() {
        var entry = catalogHolder.snapshot().entry(CATALOG_ID);
        if (entry.isEmpty()) {
            log.warn("[RagContextFormatter] catalog missing id={}", CATALOG_ID);
            return objectMapper.createObjectNode();
        }
        String json = entry.get().contentJson();
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json.strip());
        } catch (Exception e) {
            log.warn("[RagContextFormatter] bad contentJson id={}: {}", CATALOG_ID, e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode cfg, String key) {
        JsonNode node = cfg.get(key);
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private static void appendHitsBody(StringBuilder sb, List<RagClient.RagHit> hits) {
        String docNames = hits.stream()
                .map(RagClient.RagHit::docName)
                .distinct()
                .collect(Collectors.joining("、"));
        sb.append("来源文档：").append(docNames).append("\n\n");
        for (int i = 0; i < hits.size(); i++) {
            RagClient.RagHit hit = hits.get(i);
            sb.append("【").append(hit.docName()).append(" | 片段 ").append(i + 1).append("】\n");
            sb.append(hit.content()).append("\n\n");
        }
    }
}
