package com.sunshine.orchestrator.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.config.AgentRewriteProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.registry.ResolvedModelScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Query 改写 — 仅 intent / planner（路由与规划域）。
 * 提示词读 Catalog {@code rewrite.intent} / {@code rewrite.planner}。
 * RAG 检索改写已迁入 rag-service pipeline（ADR-002）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {
    private final AgentRewriteProperties rewriteProperties;
    private final PromptCatalogHolder catalogHolder;
    private final LlmGatewayClient llmGatewayClient;
    private final ModelSceneResolver modelSceneResolver;
    private final ObjectMapper objectMapper;

    public boolean isIntentEnabled() {
        return rewriteProperties.getIntent().isEnabled();
    }

    /** 规则未命中且短 query 时是否应做 intent 改写 */
    public boolean shouldRewriteIntent(String originalQuery) {
        AgentRewriteProperties.Intent cfg = rewriteProperties.getIntent();
        if (!cfg.isEnabled() || !StringUtils.hasText(originalQuery)) {
            return false;
        }
        return originalQuery.strip().length() < Math.max(1, cfg.getMaxChars());
    }

    public String rewriteForIntent(String originalQuery) {
        return rewriteForIntent(originalQuery, null, null).effectiveQuery();
    }

    public QueryRewriteOutcome rewriteForIntent(String originalQuery, String traceMessageId) {
        return rewriteForIntent(originalQuery, traceMessageId, null);
    }

    public String rewriteForPlanner(String originalQuery) {
        return rewriteForPlanner(originalQuery, null).effectiveQuery();
    }

    public QueryRewriteOutcome rewriteForPlanner(String originalQuery, String traceMessageId) {
        long start = System.nanoTime();
        AgentRewriteProperties.Planner cfg = rewriteProperties.plannerOrDefault();
        String systemPrompt = catalogText("rewrite.planner");
        if (!cfg.isEnabled() || !StringUtils.hasText(originalQuery) || !StringUtils.hasText(systemPrompt)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    QueryRewriteScenario.PLANNER.id(), originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        String user = "用户问题：" + originalQuery.strip();
        ResolvedModelScene model = modelSceneResolver.resolve(ModelSceneResolver.SCENE_REWRITE_PLANNER, null);
        String raw = llmGatewayClient.complete(
                model.effectiveModel(), model.fallbackModel(), systemPrompt, user);
        String rewritten = parseSingleQuery(raw, originalQuery);
        if (!StringUtils.hasText(rewritten)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    QueryRewriteScenario.PLANNER.id(), originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(QueryRewriteScenario.PLANNER.id(), originalQuery, rewritten, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] planner: in='{}' out='{}'",
                    abbreviate(originalQuery), abbreviate(outcome.rewrittenQuery()));
        }
        QueryRewriteTrace.record(traceMessageId, outcome);
        return outcome;
    }

    public QueryRewriteOutcome rewriteForIntent(String originalQuery, String traceMessageId, AssembledContext memory) {
        long start = System.nanoTime();
        if (!shouldRewriteIntent(originalQuery)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    QueryRewriteScenario.INTENT.id(), originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        String systemPrompt = catalogText("rewrite.intent");
        if (!StringUtils.hasText(systemPrompt)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    QueryRewriteScenario.INTENT.id(), originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        String user = RewriteConversationContext.buildUserMessage(originalQuery, memory);
        ResolvedModelScene model = modelSceneResolver.resolve(ModelSceneResolver.SCENE_REWRITE_INTENT, null);
        String raw = llmGatewayClient.complete(
                model.effectiveModel(), model.fallbackModel(), systemPrompt, user);
        String rewritten = parseSingleQuery(raw, originalQuery);
        if (!StringUtils.hasText(rewritten)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    QueryRewriteScenario.INTENT.id(), originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(QueryRewriteScenario.INTENT.id(), originalQuery, rewritten, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] intent: in='{}' out='{}' ctx={}",
                    abbreviate(originalQuery), abbreviate(outcome.rewrittenQuery()),
                    memory != null && memory.hasAnyLayer());
        }
        QueryRewriteTrace.record(traceMessageId, outcome);
        return outcome;
    }

    String parseSingleQuery(String raw, String originalQuery) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.strip();
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            String q = root.path("query").asText("").strip();
            if (StringUtils.hasText(q) && !q.equals(originalQuery.strip())) {
                return q;
            }
        } catch (Exception e) {
            log.debug("[QueryRewrite] 单条 JSON 解析失败: {}", e.getMessage());
        }
        for (String line : text.split("\\r?\\n")) {
            String q = line.strip();
            if (q.startsWith("-")) {
                q = q.substring(1).strip();
            }
            if (q.length() >= 2 && !q.equals(originalQuery.strip())) {
                return q;
            }
        }
        return "";
    }

    private String catalogText(String id) {
        return catalogHolder.snapshot().text(id).map(String::strip).orElse("");
    }

    private static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String abbreviate(String q) {
        return q.length() > 40 ? q.substring(0, 40) + "..." : q;
    }

    private static long elapsedMs(long startNano) {
        return Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L);
    }
}
