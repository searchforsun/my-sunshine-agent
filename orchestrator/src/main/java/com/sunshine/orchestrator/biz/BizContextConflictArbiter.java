package com.sunshine.orchestrator.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.audit.AuditEvent;
import com.sunshine.orchestrator.audit.AuditPublisher;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务上下文冲突仲裁（authority §5.2）：有 scene 且存在 Policy/活跃任务板时，
 * 用 LLM 判定 L3 摘要中与业务权威字段「直接矛盾」的断言并过滤，冲突决策记审计。
 * <p>原则：不做全量闲聊对撞（成本与误杀高），只判定结构化/可解析断言；
 * 低优先级材料（L3）不得覆盖高优先级权威（Policy/任务板）。LLM 判定失败按
 * {@code llm-failure-policy} 兜底（默认 drop 整段 L3——有权威块时属安全降级）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizContextConflictArbiter {

    private static final String CATALOG_ID = "context.biz-scene.conflict-check";
    private static final String AUDIT_TYPE = "BIZ_CONTEXT_CONFLICT";

    private final BusinessContextProperties properties;
    private final BusinessContextAssembler businessContextAssembler;
    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder catalogHolder;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 返回 {@code null} = 无需改动（闸门不满足 / 无权威参照 / 判定无冲突）；
     * 返回字符串 = 过滤后的 L3 块（空串 = 整段丢弃）。
     */
    public String arbitrate(String tenantId, String userId, String scene, String conversationId,
                            String messageId, String l3Block) {
        if (!properties.getConflictCheck().isEnabled() || !StringUtils.hasText(scene)
                || !StringUtils.hasText(l3Block)) {
            log.info("[BizConflict] skip enabled={} scene={} l3Len={}",
                    properties.getConflictCheck().isEnabled(), scene,
                    l3Block == null ? 0 : l3Block.length());
            return null;
        }
        String tid = StringUtils.hasText(tenantId) ? tenantId : "default";
        Instant now = Instant.now();
        String policyBlock = businessContextAssembler.renderPolicyBlock(tid, scene, now);
        String taskBlock = businessContextAssembler.renderTaskBlock(tid, userId, scene, conversationId, now);
        if (!StringUtils.hasText(policyBlock) && !StringUtils.hasText(taskBlock)) {
            log.info("[BizConflict] scene={} 无 Policy/任务板权威参照，L3 原样", scene);
            return null;
        }
        String bounded = l3Block;
        int maxChars = properties.getConflictCheck().getMaxL3Chars();
        if (maxChars > 0 && bounded.length() > maxChars) {
            bounded = bounded.substring(0, maxChars);
        }
        String template = catalogHolder.snapshot().text(CATALOG_ID).map(String::strip).orElse(null);
        if (!StringUtils.hasText(template)) {
            log.warn("[BizConflict] catalog missing id={}", CATALOG_ID);
            return handleFailure(tid, userId, scene, conversationId, messageId, policyBlock, taskBlock, "catalog-missing");
        }
        String[] parts = template.split(USER_SEPARATOR, 2);
        String system = parts[0].strip();
        String userTemplate = parts.length > 1 ? parts[1] : parts[0];
        String user = userTemplate
                .replace("{scene}", scene)
                .replace("{policy}", blankToPlaceholder(policyBlock))
                .replace("{taskBoard}", blankToPlaceholder(taskBlock))
                .replace("{l3}", bounded);
        String raw;
        try {
            raw = llmGatewayClient.complete(system, user);
        } catch (Exception e) {
            log.warn("[BizConflict] llm failed scene={}: {}", scene, e.getMessage());
            return handleFailure(tid, userId, scene, conversationId, messageId, policyBlock, taskBlock, "llm-error");
        }
        List<String> snippets = parseFilteredSnippets(raw);
        if (snippets == null) {
            log.warn("[BizConflict] parse failed scene={} raw={}", scene, truncate(raw));
            return handleFailure(tid, userId, scene, conversationId, messageId, policyBlock, taskBlock, "parse-error");
        }
        if (snippets.isEmpty()) {
            log.info("[BizConflict] scene={} 判定无冲突，L3 原样", scene);
            return null;
        }
        String filtered = removeSnippets(l3Block, snippets);
        auditPublisher.publish(buildEvent(tid, userId, scene, conversationId, messageId,
                "filtered", policyBlock, taskBlock, snippets, l3Block.length(), filtered.length()));
        log.info("[BizConflict] scene={} 过滤 {} 段冲突摘要 l3={}->{}",
                scene, snippets.size(), l3Block.length(), filtered.length());
        return filtered;
    }

    private String handleFailure(String tid, String userId, String scene, String conversationId,
                                 String messageId, String policyBlock, String taskBlock, String reason) {
        auditPublisher.publish(buildEvent(tid, userId, scene, conversationId, messageId,
                reason, policyBlock, taskBlock, List.of(), 0, 0));
        boolean drop = !"keep".equalsIgnoreCase(properties.getConflictCheck().getLlmFailurePolicy());
        log.warn("[BizConflict] scene={} 兜底={} reason={}", scene, drop ? "drop-l3" : "keep-l3", reason);
        return drop ? "" : null;
    }

    /** 解析 LLM JSON 输出：{"filter":[{"snippet":"原文片段","reason":"..."}]}；无法解析返回 null。 */
    private List<String> parseFilteredSnippets(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            JsonNode filter = node.get("filter");
            if (filter == null || !filter.isArray()) {
                return null;
            }
            List<String> snippets = new ArrayList<>();
            for (JsonNode item : filter) {
                JsonNode snippet = item.get("snippet");
                if (snippet != null && snippet.isTextual() && StringUtils.hasText(snippet.asText())) {
                    snippets.add(snippet.asText());
                }
            }
            return snippets;
        } catch (Exception e) {
            log.debug("[BizConflict] json parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** 按段落（空行分隔）拆分 L3，移除任一命中片段所属段落。 */
    private String removeSnippets(String l3, List<String> snippets) {
        String[] paragraphs = l3.split("\\n\\s*\\n");
        StringBuilder sb = new StringBuilder();
        int removed = 0;
        for (String paragraph : paragraphs) {
            boolean hit = false;
            for (String snippet : snippets) {
                if (paragraph.contains(snippet)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                removed++;
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(paragraph.strip());
        }
        log.info("[BizConflict] 移除段落 {}/{}", removed, paragraphs.length);
        return sb.toString();
    }

    private AuditEvent buildEvent(String tid, String userId, String scene, String conversationId,
                                  String messageId, String status, String policyBlock, String taskBlock,
                                  List<String> snippets, int sourceLen, int filteredLen) {
        String payload = null;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "scene", scene,
                    "hasPolicy", StringUtils.hasText(policyBlock),
                    "hasTaskBoard", StringUtils.hasText(taskBlock),
                    "filteredSnippets", snippets,
                    "sourceL3Len", sourceLen,
                    "filteredL3Len", filteredLen));
        } catch (Exception e) {
            log.warn("[BizConflict] audit payload serialize failed: {}", e.getMessage());
        }
        return new AuditEvent(
                UUID.randomUUID().toString().replace("-", ""),
                conversationId != null ? conversationId : "",
                messageId != null ? messageId : "",
                userId != null ? userId : "",
                tid,
                AUDIT_TYPE,
                status,
                null,
                sourceLen,
                payload,
                Instant.now());
    }

    private static String blankToPlaceholder(String block) {
        return StringUtils.hasText(block) ? block : "（无）";
    }

    private static String truncate(String raw) {
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "...";
    }

    /** Catalog 模板内 system 与 user 段的分隔行（SSOT：提示词正文一律入 Catalog）。 */
    private static final String USER_SEPARATOR = "=== USER ===";
}
