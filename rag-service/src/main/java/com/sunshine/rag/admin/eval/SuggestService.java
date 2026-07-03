package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.DocumentCatalogService;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.admin.config.ConfigBundlePathUtils;
import com.sunshine.rag.admin.config.ConfigVersionService;
import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.admin.eval.dto.EvalSuggestRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import com.sunshine.rag.admin.eval.dto.TextSuggestionItem;
import com.sunshine.rag.client.LlmGatewayClient;
import com.sunshine.rag.config.RagEvalProperties;
import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.entity.EvalReportEntity;
import com.sunshine.rag.entity.EvalSuiteEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.EvalSuiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final EvalReportRepository evalReportRepository;
    private final EvalJobRepository evalJobRepository;
    private final EvalSuiteRepository evalSuiteRepository;
    private final ConfigVersionService configVersionService;
    private final DocumentCatalogService documentCatalogService;
    private final LlmGatewayClient llmGatewayClient;
    private final RagEvalProperties evalProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public EvalSuggestResult suggest(String tenantId, EvalSuggestRequest request) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        EvalReportEntity report = evalReportRepository.findById(request.reportId())
                .orElseThrow(() -> new IllegalArgumentException("eval report 不存在: " + request.reportId()));
        EvalJobEntity job = evalJobRepository.findById(report.getJobId())
                .orElseThrow(() -> new IllegalArgumentException("eval job 不存在: " + report.getJobId()));
        String kbId = request.kbId() != null && !request.kbId().isBlank()
                ? request.kbId().strip()
                : job.getKbId();
        Map<String, Object> configSnapshot = resolveConfigSnapshot(tid, kbId, job);
        Map<String, Object> kbSummary = buildKbSummary(tid, kbId);
        Map<String, Object> evalContext = EvalSuggestContextBuilder.build(report, objectMapper);
        String userPrompt = buildUserPrompt(report, job, configSnapshot, kbSummary, evalContext);
        String systemPrompt = evalProperties.getSuggest().getSystemPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalStateException("rag.eval.suggest.system-prompt 未配置，请检查 Nacos sunshine-rag.yaml");
        }
        String raw = llmGatewayClient.complete(
                evalProperties.getSuggest().getModel(),
                systemPrompt,
                userPrompt);
        EvalSuggestResult parsed = parseSuggestResponse(raw, configSnapshot);
        @SuppressWarnings("unchecked")
        List<String> failureModes = evalContext.get("failureModes") instanceof List<?> list
                ? (List<String>) list
                : List.of();
        EvalSuggestResult result = EvalSuggestValidator.validate(parsed, failureModes);
        try {
            report.setSuggestionsJson(objectMapper.writeValueAsString(result));
            evalReportRepository.save(report);
        } catch (Exception e) {
            log.warn("[RAG] suggestions_json 持久化失败: {}", e.getMessage());
        }
        log.info("[RAG] eval suggest reportId={} config={} text={}",
                report.getId(), result.suggestions().size(), result.textSuggestions().size());
        return result;
    }

    private Map<String, Object> resolveConfigSnapshot(String tenantId, String kbId, EvalJobEntity job) {
        if (job.getConfigVersionId() != null) {
            return configVersionService.getEffective(
                    tenantId, kbId, "version", job.getConfigVersionId());
        }
        return configVersionService.getDraftView(tenantId, kbId).payload();
    }

    private Map<String, Object> buildKbSummary(String tenantId, String kbId) {
        List<DocumentSummary> docs = documentCatalogService.listDocuments(tenantId, kbId);
        int docCount = docs.size();
        int chunkCount = docs.stream().mapToInt(DocumentSummary::chunkCount).sum();
        List<String> topDocNames = docs.stream()
                .sorted(Comparator.comparingInt(DocumentSummary::chunkCount).reversed())
                .limit(5)
                .map(DocumentSummary::displayName)
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("docCount", docCount);
        summary.put("chunkCount", chunkCount);
        summary.put("topDocNames", topDocNames);
        return summary;
    }

    private String buildUserPrompt(
            EvalReportEntity report,
            EvalJobEntity job,
            Map<String, Object> configSnapshot,
            Map<String, Object> kbSummary,
            Map<String, Object> evalContext) {
        String suiteKey = resolveSuiteKey(job);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", report.getId());
        payload.put("kbId", job.getKbId());
        payload.put("suite", job.getSuite());
        payload.put("suiteKey", suiteKey);
        payload.put("recallAt5", report.getRecallAt5());
        payload.put("mrr", report.getMrr());
        payload.put("passedGate", report.getPassedGate());
        payload.put("configSnapshot", configSnapshot);
        payload.put("kbSummary", kbSummary);
        payload.putAll(evalContext);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    private String resolveSuiteKey(EvalJobEntity job) {
        if (job.getSuiteId() != null) {
            return evalSuiteRepository.findById(job.getSuiteId())
                    .map(EvalSuiteEntity::getSuiteKey)
                    .orElse(EvalSuiteKeys.DEFAULT_SUITE);
        }
        return EvalSuiteKeys.DEFAULT_SUITE;
    }

    private EvalSuggestResult parseSuggestResponse(String raw, Map<String, Object> configSnapshot) {
        if (raw == null || raw.isBlank()) {
            return new EvalSuggestResult("模型未返回有效内容", List.of(), List.of());
        }
        try {
            String json = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            String diagnosis = stringVal(parsed.get("diagnosis"));
            List<ConfigSuggestionItem> rawConfigItems = parseConfigSuggestions(parsed, configSnapshot);
            List<TextSuggestionItem> textItems = new ArrayList<>(parseTextSuggestions(parsed));
            List<ConfigSuggestionItem> configItems = new ArrayList<>();
            for (ConfigSuggestionItem item : rawConfigItems) {
                if (isTextPath(item.path())) {
                    textItems.add(new TextSuggestionItem(
                            item.path(),
                            "prompt",
                            item.current() != null ? String.valueOf(item.current()) : "",
                            item.proposed() != null ? String.valueOf(item.proposed()) : "",
                            item.reason()));
                } else {
                    configItems.add(item);
                }
            }
            return new EvalSuggestResult(diagnosis, configItems, textItems);
        } catch (Exception e) {
            log.warn("[RAG] suggest 解析失败: {}", e.getMessage());
            return new EvalSuggestResult("解析失败: " + e.getMessage(), List.of(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private List<ConfigSuggestionItem> parseConfigSuggestions(Map<String, Object> parsed, Map<String, Object> configSnapshot) {
        Object suggestionsObj = parsed.get("suggestions");
        if (suggestionsObj == null) {
            suggestionsObj = parsed.get("configSuggestions");
        }
        if (!(suggestionsObj instanceof List<?> list)) {
            return List.of();
        }
        List<ConfigSuggestionItem> items = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) row;
            String path = stringVal(map.get("path"));
            if (path.isBlank()) {
                continue;
            }
            Object current = map.get("current");
            if (current == null) {
                current = ConfigBundlePathUtils.getPath(configSnapshot, path);
            }
            items.add(new ConfigSuggestionItem(
                    path,
                    current,
                    map.get("proposed"),
                    stringVal(map.get("reason"))));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private List<TextSuggestionItem> parseTextSuggestions(Map<String, Object> parsed) {
        Object textObj = parsed.get("textSuggestions");
        if (!(textObj instanceof List<?> list)) {
            return List.of();
        }
        List<TextSuggestionItem> items = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) row;
            String proposed = stringVal(map.get("proposed"));
            if (proposed.isBlank()) {
                continue;
            }
            items.add(new TextSuggestionItem(
                    stringVal(map.get("target")),
                    stringVal(map.get("kind")),
                    stringVal(map.get("current")),
                    proposed,
                    stringVal(map.get("reason"))));
        }
        return items;
    }

    private static boolean isTextPath(String path) {
        return path.contains("systemPrompt") || path.contains("userPrompt") || path.endsWith(".prompt");
    }

    private static String extractJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return output.substring(start, end + 1);
        }
        throw new IllegalStateException("输出非 JSON");
    }

    private static String stringVal(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
