package com.sunshine.rag.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.config.RewriteSettings;
import com.sunshine.rag.client.LlmGatewayClient;
import com.sunshine.rag.config.RagRewriteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** RAG 检索链路 Query 改写 — rag / hyde / empty-recall */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewritePipeline {
    private final RagRewriteProperties rewriteProperties;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;

    /** 使用 kb 级 rewrite 配置（EffectiveConfigResolver SSOT） */
    public boolean isRagEnabled(RewriteSettings settings) {
        return settings != null && settings.rag().enabled()
                && org.springframework.util.StringUtils.hasText(settings.rag().systemPrompt());
    }

    public boolean isHydeEnabled(RewriteSettings settings) {
        if (settings == null) {
            return false;
        }
        RewriteSettings.RewriteHydeSettings hyde = settings.rag().hyde();
        return hyde != null && hyde.enabled()
                && org.springframework.util.StringUtils.hasText(hyde.systemPrompt());
    }

    public boolean isEmptyRecallEnabled(RewriteSettings settings) {
        return settings != null && settings.emptyRecall().enabled()
                && org.springframework.util.StringUtils.hasText(settings.emptyRecall().systemPrompt());
    }

    public QueryRewriteOutcome rewriteForRag(String originalQuery, RewriteSettings settings) {
        long start = System.nanoTime();
        RewriteSettings.RewriteRagSettings cfg = settings.rag();
        if (!isRagEnabled(settings) || !StringUtils.hasText(originalQuery)) {
            return QueryRewriteOutcome.skipped("rag", originalQuery, elapsedMs(start));
        }
        String user = "用户问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.model(), cfg.systemPrompt(), user);
        String rewritten = parseSingleQuery(raw, originalQuery);
        if (!StringUtils.hasText(rewritten)) {
            return QueryRewriteOutcome.skipped("rag", originalQuery, elapsedMs(start));
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("rag", originalQuery, rewritten, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] rag: in='{}' out='{}'", abbreviate(originalQuery), abbreviate(outcome.rewrittenQuery()));
        }
        return outcome;
    }

    public QueryRewriteOutcome hydeForRag(String originalQuery, RewriteSettings settings) {
        long start = System.nanoTime();
        RewriteSettings.RewriteRagSettings ragCfg = settings.rag();
        RewriteSettings.RewriteHydeSettings hydeCfg = ragCfg.hyde();
        if (!isHydeEnabled(settings) || !StringUtils.hasText(originalQuery)) {
            return QueryRewriteOutcome.skipped("hyde", originalQuery, elapsedMs(start));
        }
        String model = StringUtils.hasText(hydeCfg.model()) ? hydeCfg.model() : ragCfg.model();
        String user = "用户问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(model, hydeCfg.systemPrompt(), user);
        String document = parseHydeDocument(raw, hydeCfg.maxChars());
        if (!StringUtils.hasText(document)) {
            return QueryRewriteOutcome.skipped("hyde", originalQuery, elapsedMs(start));
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("hyde", originalQuery, document, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] hyde: in='{}' docLen={}", abbreviate(originalQuery), outcome.rewrittenQuery().length());
        }
        return outcome;
    }

    public EmptyRecallRewrite rewriteEmptyRecall(String originalQuery, RewriteSettings settings) {
        long start = System.nanoTime();
        RewriteSettings.RewriteEmptyRecallSettings cfg = settings.emptyRecall();
        if (!isEmptyRecallEnabled(settings) || !StringUtils.hasText(originalQuery)) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped("empty-recall", originalQuery, elapsedMs(start));
            return new EmptyRecallRewrite(List.of(), skipped);
        }
        int n = Math.max(1, Math.min(cfg.maxAlternatives(), 3));
        String system = cfg.systemPrompt().formatted(n);
        String user = "原始问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.model(), system, user);
        List<String> queries = parseQueries(raw, originalQuery, n);
        QueryRewriteOutcome outcome = QueryRewriteOutcome.emptyRecall(originalQuery, queries, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] empty-recall: in='{}' alts={}", abbreviate(originalQuery), queries);
        }
        return new EmptyRecallRewrite(queries, outcome);
    }

    public boolean isRagEnabled() {
        return rewriteProperties.getRag().isEnabled();
    }

    public boolean isHydeEnabled() {
        RagRewriteProperties.Hyde hyde = rewriteProperties.getRag().getHyde();
        return hyde != null && hyde.isEnabled();
    }

    public boolean isEmptyRecallEnabled() {
        return rewriteProperties.getEmptyRecall().isEnabled();
    }

    public QueryRewriteOutcome rewriteForRag(String originalQuery) {
        long start = System.nanoTime();
        RagRewriteProperties.Rag cfg = rewriteProperties.getRag();
        if (!cfg.isEnabled() || !StringUtils.hasText(originalQuery) || !StringUtils.hasText(cfg.getSystemPrompt())) {
            return QueryRewriteOutcome.skipped("rag", originalQuery, elapsedMs(start));
        }
        String user = "用户问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.getModel(), cfg.getSystemPrompt(), user);
        String rewritten = parseSingleQuery(raw, originalQuery);
        if (!StringUtils.hasText(rewritten)) {
            return QueryRewriteOutcome.skipped("rag", originalQuery, elapsedMs(start));
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("rag", originalQuery, rewritten, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] rag: in='{}' out='{}'", abbreviate(originalQuery), abbreviate(outcome.rewrittenQuery()));
        }
        return outcome;
    }

    public QueryRewriteOutcome hydeForRag(String originalQuery) {
        long start = System.nanoTime();
        RagRewriteProperties.Rag ragCfg = rewriteProperties.getRag();
        RagRewriteProperties.Hyde hydeCfg = ragCfg.getHyde();
        if (hydeCfg == null || !hydeCfg.isEnabled() || !StringUtils.hasText(originalQuery)
                || !StringUtils.hasText(hydeCfg.getSystemPrompt())) {
            return QueryRewriteOutcome.skipped("hyde", originalQuery, elapsedMs(start));
        }
        String model = StringUtils.hasText(hydeCfg.getModel()) ? hydeCfg.getModel() : ragCfg.getModel();
        String user = "用户问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(model, hydeCfg.getSystemPrompt(), user);
        String document = parseHydeDocument(raw, hydeCfg.getMaxChars());
        if (!StringUtils.hasText(document)) {
            return QueryRewriteOutcome.skipped("hyde", originalQuery, elapsedMs(start));
        }
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("hyde", originalQuery, document, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] hyde: in='{}' docLen={}", abbreviate(originalQuery), outcome.rewrittenQuery().length());
        }
        return outcome;
    }

    public record EmptyRecallRewrite(List<String> alternatives, QueryRewriteOutcome outcome) {
    }

    public EmptyRecallRewrite rewriteEmptyRecall(String originalQuery) {
        long start = System.nanoTime();
        RagRewriteProperties.EmptyRecall cfg = rewriteProperties.getEmptyRecall();
        if (!cfg.isEnabled() || !StringUtils.hasText(originalQuery) || !StringUtils.hasText(cfg.getSystemPrompt())) {
            QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped("empty-recall", originalQuery, elapsedMs(start));
            return new EmptyRecallRewrite(List.of(), skipped);
        }
        int n = Math.max(1, Math.min(cfg.getMaxAlternatives(), 3));
        String system = cfg.getSystemPrompt().formatted(n);
        String user = "原始问题：" + originalQuery.strip();
        String raw = llmGatewayClient.complete(cfg.getModel(), system, user);
        List<String> queries = parseQueries(raw, originalQuery, n);
        QueryRewriteOutcome outcome = QueryRewriteOutcome.emptyRecall(originalQuery, queries, elapsedMs(start));
        if (outcome.applied()) {
            log.info("[QueryRewrite] empty-recall: in='{}' alts={}", abbreviate(originalQuery), queries);
        }
        return new EmptyRecallRewrite(queries, outcome);
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
        List<String> fromJson = tryParseJson(raw, originalQuery, max);
        if (!fromJson.isEmpty()) {
            return fromJson;
        }
        Set<String> lines = new LinkedHashSet<>();
        for (String line : raw.strip().split("\\r?\\n")) {
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

    private static String clipHyde(String text, int maxChars) {
        String s = text.strip();
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, maxChars).strip();
    }

    private static String abbreviate(String q) {
        return q.length() > 40 ? q.substring(0, 40) + "..." : q;
    }

    private static long elapsedMs(long startNano) {
        return Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L);
    }
}
