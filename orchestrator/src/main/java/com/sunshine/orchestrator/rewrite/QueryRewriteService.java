package com.sunshine.orchestrator.rewrite;



import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.sunshine.orchestrator.client.LlmGatewayClient;

import com.sunshine.orchestrator.config.AgentRewriteProperties;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.springframework.util.StringUtils;



import java.util.LinkedHashSet;

import java.util.List;

import java.util.Set;



/**

 * Query 改写 — rag / intent / empty-recall 分场景（Nacos agent.rewrite.*，默认均关闭）。

 */

@Slf4j

@Service

@RequiredArgsConstructor

public class QueryRewriteService {



    private final AgentRewriteProperties rewriteProperties;

    private final LlmGatewayClient llmGatewayClient;

    private final ObjectMapper objectMapper;



    public boolean isRagEnabled() {

        return rewriteProperties.getRag().isEnabled();

    }

    public boolean isHydeEnabled() {
        AgentRewriteProperties.Hyde hyde = rewriteProperties.getRag().getHyde();
        return hyde != null && hyde.isEnabled();
    }



    public boolean isEmptyRecallEnabled() {

        return rewriteProperties.getEmptyRecall().isEnabled();

    }



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



    /**

     * RAG 检索前优化 query；未启用或失败时返回原文。

     */

    public String rewriteForRag(String originalQuery) {
        return rewriteForRag(originalQuery, null).effectiveQuery();
    }

    public QueryRewriteOutcome rewriteForRag(String originalQuery, String traceMessageId) {
        long start = System.nanoTime();
        AgentRewriteProperties.Rag cfg = rewriteProperties.getRag();
        if (!cfg.isEnabled() || !StringUtils.hasText(originalQuery) || !StringUtils.hasText(cfg.getSystemPrompt())) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "rag", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        String user = "用户问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.getModel(), cfg.getSystemPrompt(), user);
        String rewritten = parseSingleQuery(raw, originalQuery);
        if (!StringUtils.hasText(rewritten)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "rag", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("rag", originalQuery, rewritten, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] rag: in='{}' out='{}'",
                    abbreviate(originalQuery), abbreviate(outcome.rewrittenQuery()));
        }
        QueryRewriteTrace.record(traceMessageId, outcome);
        return outcome;
    }

    /**
     * HyDE：根据用户问题生成假想制度段落作为检索 query；未启用或失败时返回 skipped。
     */
    public QueryRewriteOutcome hydeForRag(String originalQuery) {
        return hydeForRag(originalQuery, null);
    }

    public QueryRewriteOutcome hydeForRag(String originalQuery, String traceMessageId) {
        long start = System.nanoTime();
        AgentRewriteProperties.Rag ragCfg = rewriteProperties.getRag();
        AgentRewriteProperties.Hyde hydeCfg = ragCfg.getHyde();
        if (hydeCfg == null || !hydeCfg.isEnabled() || !StringUtils.hasText(originalQuery)
                || !StringUtils.hasText(hydeCfg.getSystemPrompt())) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "hyde", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        String model = StringUtils.hasText(hydeCfg.getModel()) ? hydeCfg.getModel() : ragCfg.getModel();
        String user = "用户问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(model, hydeCfg.getSystemPrompt(), user);
        String document = parseHydeDocument(raw, hydeCfg.getMaxChars());
        if (!StringUtils.hasText(document)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "hyde", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("hyde", originalQuery, document, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] hyde: in='{}' docLen={}",
                    abbreviate(originalQuery), outcome.rewrittenQuery().length());
        }
        QueryRewriteTrace.record(traceMessageId, outcome);
        return outcome;
    }



    /**

     * 意图分类前补全短 query；未启用、过长或失败时返回原文。

     */

    public String rewriteForIntent(String originalQuery) {
        return rewriteForIntent(originalQuery, null).effectiveQuery();
    }

    /** Plan-Workflow Planner 调用前优化 query；未启用或失败时返回原文 */
    public String rewriteForPlanner(String originalQuery) {
        return rewriteForPlanner(originalQuery, null).effectiveQuery();
    }

    public QueryRewriteOutcome rewriteForPlanner(String originalQuery, String traceMessageId) {
        long start = System.nanoTime();
        AgentRewriteProperties.Planner cfg = rewriteProperties.plannerOrDefault();
        if (!cfg.isEnabled() || !StringUtils.hasText(originalQuery) || !StringUtils.hasText(cfg.getSystemPrompt())) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "planner", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        String user = "用户问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.getModel(), cfg.getSystemPrompt(), user);
        String rewritten = parseSingleQuery(raw, originalQuery);
        if (!StringUtils.hasText(rewritten)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "planner", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("planner", originalQuery, rewritten, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] planner: in='{}' out='{}'",
                    abbreviate(originalQuery), abbreviate(outcome.rewrittenQuery()));
        }
        QueryRewriteTrace.record(traceMessageId, outcome);
        return outcome;
    }

    public QueryRewriteOutcome rewriteForIntent(String originalQuery, String traceMessageId) {
        long start = System.nanoTime();
        if (!shouldRewriteIntent(originalQuery)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "intent", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        AgentRewriteProperties.Intent cfg = rewriteProperties.getIntent();
        if (!StringUtils.hasText(cfg.getSystemPrompt())) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "intent", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        String user = "用户输入：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.getModel(), cfg.getSystemPrompt(), user);
        String rewritten = parseSingleQuery(raw, originalQuery);
        if (!StringUtils.hasText(rewritten)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "intent", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return skipped;
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("intent", originalQuery, rewritten, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] intent: in='{}' out='{}'",
                    abbreviate(originalQuery), abbreviate(outcome.rewrittenQuery()));
        }
        QueryRewriteTrace.record(traceMessageId, outcome);
        return outcome;
    }



    /**

     * 为零命中检索生成替代 query；失败或未启用时返回空列表。

     */

    public List<String> rewriteEmptyRecall(String originalQuery) {
        return rewriteEmptyRecall(originalQuery, null).alternatives();
    }

    public EmptyRecallRewrite rewriteEmptyRecall(String originalQuery, String traceMessageId) {
        long start = System.nanoTime();
        AgentRewriteProperties.EmptyRecall cfg = rewriteProperties.getEmptyRecall();
        if (!cfg.isEnabled() || !StringUtils.hasText(originalQuery) || !StringUtils.hasText(cfg.getSystemPrompt())) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                    "empty-recall", originalQuery, elapsedMs(start));
            QueryRewriteTrace.record(traceMessageId, skipped);
            return new EmptyRecallRewrite(List.of(), skipped);
        }
        int n = Math.max(1, Math.min(cfg.getMaxAlternatives(), 3));
        String system = cfg.getSystemPrompt().formatted(n);
        String user = "原始问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.getModel(), system, user);
        List<String> queries = parseQueries(raw, originalQuery, n);
        QueryRewriteOutcome outcome = QueryRewriteOutcome.emptyRecall(originalQuery, queries, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] empty-recall: in='{}' alts={}",
                    abbreviate(originalQuery), queries);
        }
        QueryRewriteTrace.record(traceMessageId, outcome);
        return new EmptyRecallRewrite(queries, outcome);
    }

    public record EmptyRecallRewrite(List<String> alternatives, QueryRewriteOutcome outcome) {
    }

    String parseHydeDocument(String raw, int maxChars) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.strip();
        int limit = Math.max(80, maxChars);
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            for (String field : List.of("document", "hyde", "passage", "text")) {
                String doc = root.path(field).asText("").strip();
                if (StringUtils.hasText(doc)) {
                    return clipHyde(doc, limit);
                }
            }
        } catch (Exception e) {
            log.debug("[QueryRewrite] HyDE JSON 解析失败: {}", e.getMessage());
        }
        String plain = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").strip();
        if (plain.startsWith("{")) {
            return "";
        }
        return clipHyde(plain, limit);
    }

    private static String clipHyde(String text, int maxChars) {
        String s = text.strip();
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars).strip();
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



    List<String> parseQueries(String raw, String originalQuery, int max) {

        if (!StringUtils.hasText(raw)) {

            return List.of();

        }

        String text = raw.strip();

        List<String> fromJson = tryParseJson(text, originalQuery, max);

        if (!fromJson.isEmpty()) {

            return fromJson;

        }

        Set<String> lines = new LinkedHashSet<>();

        for (String line : text.split("\\r?\\n")) {

            String q = line.strip();

            if (q.startsWith("-")) {

                q = q.substring(1).strip();

            }

            if (q.length() >= 2 && !q.equals(originalQuery.strip())) {

                lines.add(q);

            }

            if (lines.size() >= max) {

                break;

            }

        }

        return List.copyOf(lines);

    }



    private List<String> tryParseJson(String text, String originalQuery, int max) {

        try {

            JsonNode root = objectMapper.readTree(extractJson(text));

            JsonNode arr = root.path("queries");

            if (!arr.isArray()) {

                return List.of();

            }

            Set<String> out = new LinkedHashSet<>();

            for (JsonNode node : arr) {

                String q = node.asText("").strip();

                if (StringUtils.hasText(q) && !q.equals(originalQuery.strip())) {

                    out.add(q);

                }

                if (out.size() >= max) {

                    break;

                }

            }

            return List.copyOf(out);

        } catch (Exception e) {

            log.debug("[QueryRewrite] JSON 解析失败: {}", e.getMessage());

            return List.of();

        }

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


