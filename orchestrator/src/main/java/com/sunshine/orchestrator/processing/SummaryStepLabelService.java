package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolSummarizeOutputResponse;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** agent / RAG after 等业务摘要 — SSOT：Nacos agent.timeline.agent / rag-after */
@Service
@RefreshScope
@RequiredArgsConstructor
public class SummaryStepLabelService {

    private static final Pattern HIT_COUNT = Pattern.compile("(\\d+)");
    private static final Pattern RAG_HIT = Pattern.compile("命中\\s*(\\d+)\\s*条");
    private static final Pattern RAG_SOURCE = Pattern.compile("来源[：:](.+)");
    private static final int RAG_SOURCE_CLIP = 80;

    private final AgentPromptProperties agentPromptProperties;
    private final ToolCatalogService toolCatalogService;

    @PostConstruct
    void init() {
        SummaryStepLabels.bind(this);
    }

    public String agentBefore(String clippedQuery) {
        return apply(agentTimeline().getBefore(), vars(clippedQuery, null, null));
    }

    public String agentActive(String clippedQuery) {
        return apply(agentTimeline().getActive(), vars(clippedQuery, null, null));
    }

    public String agentProgress(String clippedQuery) {
        return apply(agentTimeline().getProgress(), vars(clippedQuery, null, null));
    }

    public String agentAfter(String userQuery, String ragDetailHint) {
        String q = StepSummarizer.clipQuery(userQuery);
        String detail = ragDetailHint;
        AgentPromptProperties.AgentTimeline cfg = agentTimeline();
        if (detail == null && userQuery == null) {
            return textOrDefault(cfg.getAfterNoContext(), "完成问题分析，开始生成回复");
        }
        if (detail == null) {
            return apply(textOrDefault(cfg.getAfterOutline(), "已梳理{query}的作答要点"), vars(q, null, null));
        }
        if (detail.contains("0 条")) {
            return apply(textOrDefault(cfg.getAfterZeroHits(), "知识库暂无{query}的匹配内容，将结合通用知识作答"),
                    vars(q, null, null));
        }
        Matcher matcher = HIT_COUNT.matcher(detail);
        if (matcher.find()) {
            return apply(textOrDefault(cfg.getAfterWithHits(), "已从 {hitCount} 条文档中提取与{query}相关的关键信息"),
                    vars(q, matcher.group(1), null));
        }
        return apply(textOrDefault(cfg.getAfterDefault(), "已完成对{query}的分析，开始生成回复"), vars(q, null, null));
    }

    public String ragAfter(String clippedQuery, String detail, StepMetadata metadata) {
        AgentPromptProperties.RagAfterTimeline cfg = ragAfterTimeline();
        if (metadata != null && metadata.hitCount() != null && metadata.hitCount() > 0) {
            String sources = metadata.sourcesLabel();
            if (!sources.isBlank()) {
                return apply(textOrDefault(cfg.getHitsWithSources(), "找到 {hitCount} 条参考片段，来源：{sources}"),
                        vars(clippedQuery, String.valueOf(metadata.hitCount()), sources));
            }
            return apply(textOrDefault(cfg.getHitsWithQuery(), "找到 {hitCount} 条与{query}相关的参考文档"),
                    vars(clippedQuery, String.valueOf(metadata.hitCount()), null));
        }
        String input = detail != null ? detail : "";
        ToolSummarizeOutputResponse summarized;
        if (isAlreadyRagSummary(input)) {
            String summary = input.strip();
            Matcher hitMatcher = RAG_HIT.matcher(summary);
            boolean zeroHit = hitMatcher.find() && "0".equals(hitMatcher.group(1));
            summarized = new ToolSummarizeOutputResponse(summary, zeroHit, false);
        } else {
            summarized = toolCatalogService.summarizeOutputDetail("search_knowledge", input);
        }
        String summary = summarized.summary();
        if (summarized.zeroHit() || summarized.empty()) {
            return apply(textOrDefault(cfg.getZeroHits(), "未找到与{query}直接相关的制度或文档"),
                    vars(clippedQuery, null, null));
        }
        Matcher countMatcher = RAG_HIT.matcher(summary);
        if (!countMatcher.find()) {
            return apply(textOrDefault(cfg.getGenericDone(), "已完成针对{query}的知识库检索"),
                    vars(clippedQuery, null, null));
        }
        String hitCount = countMatcher.group(1);
        Matcher sourceMatcher = RAG_SOURCE.matcher(summary);
        if (sourceMatcher.find()) {
            String docNames = clipRagSource(sourceMatcher.group(1));
            return apply(textOrDefault(cfg.getHitsWithSources(), "找到 {hitCount} 条参考片段，来源：{sources}"),
                    vars(clippedQuery, hitCount, docNames));
        }
        return apply(textOrDefault(cfg.getHitsWithQuery(), "找到 {hitCount} 条与{query}相关的参考文档"),
                vars(clippedQuery, hitCount, null));
    }

    private AgentPromptProperties.AgentTimeline agentTimeline() {
        AgentPromptProperties.Timeline timeline = agentPromptProperties.timelineOrDefault();
        return timeline.getAgent() != null ? timeline.getAgent() : new AgentPromptProperties.AgentTimeline();
    }

    private AgentPromptProperties.RagAfterTimeline ragAfterTimeline() {
        AgentPromptProperties.Timeline timeline = agentPromptProperties.timelineOrDefault();
        return timeline.getRagAfter() != null ? timeline.getRagAfter() : new AgentPromptProperties.RagAfterTimeline();
    }

    private static String clipRagSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String line = raw.strip();
        int fragment = line.indexOf('【');
        if (fragment >= 0) {
            line = line.substring(0, fragment).trim();
        }
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline).trim();
        }
        if (line.length() > RAG_SOURCE_CLIP) {
            return line.substring(0, RAG_SOURCE_CLIP) + "…";
        }
        return line;
    }

    private static boolean isAlreadyRagSummary(String detail) {
        if (detail == null || detail.isBlank() || detail.length() > 200 || detail.contains("【")) {
            return false;
        }
        return RAG_HIT.matcher(detail.strip()).find();
    }

    private static Map<String, String> vars(String clippedQuery, String hitCount, String sources) {
        Map<String, String> map = new HashMap<>();
        map.put("query", clippedQuery != null ? clippedQuery : "");
        map.put("hitCount", hitCount != null ? hitCount : "");
        map.put("sources", sources != null ? sources : "");
        return map;
    }

    private static String apply(String template, Map<String, String> vars) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        String result = template.strip();
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
