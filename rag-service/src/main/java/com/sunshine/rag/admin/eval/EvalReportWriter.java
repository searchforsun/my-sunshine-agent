package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagEvalProperties;
import com.sunshine.rag.storage.RagStorageFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EvalReportWriter {

    private static final DateTimeFormatter RUN_TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RagEvalProperties evalProperties;
    private final RagStorageFacade storageFacade;
    private final ObjectMapper objectMapper;

    public record WrittenReport(
            String jsonObjectKey,
            String mdObjectKey,
            String runTag,
            Path jsonPath,
            Path mdPath) {
    }

    public WrittenReport write(Map<String, Object> report, String tenantId, long jobId) {
        try {
            String runTag = String.valueOf(report.getOrDefault("run_tag", RUN_TAG.format(LocalDateTime.now())));
            String jsonName = "rag-eval-report-" + runTag + ".json";
            String mdName = "rag-eval-report-" + runTag + ".md";
            String jsonText = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            String mdText = buildMarkdown(report);
            RagStorageFacade.WrittenObject jsonWritten = storageFacade.putReportText(tenantId, jobId, jsonName, jsonText);
            RagStorageFacade.WrittenObject mdWritten = storageFacade.putReportText(tenantId, jobId, mdName, mdText);
            return new WrittenReport(
                    jsonWritten.objectKey(),
                    mdWritten.objectKey(),
                    runTag,
                    jsonWritten.localPath(),
                    mdWritten.localPath());
        } catch (Exception e) {
            throw new IllegalStateException("写入评测报告失败: " + e.getMessage(), e);
        }
    }

    /** 单测/本地 dev 导出（无 jobId） */
    public WrittenReport writeLocal(Map<String, Object> report) {
        try {
            String runTag = String.valueOf(report.getOrDefault("run_tag", RUN_TAG.format(LocalDateTime.now())));
            Path dir = resolveReportDir();
            Files.createDirectories(dir);
            Path jsonPath = dir.resolve("rag-eval-report-" + runTag + ".json");
            Path mdPath = dir.resolve("rag-eval-report-" + runTag + ".md");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), report);
            Files.writeString(mdPath, buildMarkdown(report));
            return new WrittenReport(jsonPath.toString(), mdPath.toString(), runTag, jsonPath, mdPath);
        } catch (Exception e) {
            throw new IllegalStateException("写入评测报告失败: " + e.getMessage(), e);
        }
    }

    private Path resolveReportDir() {
        Path configured = Path.of(evalProperties.getReportDir());
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir", ".")).resolve(configured);
        }
        return configured;
    }

    @SuppressWarnings("unchecked")
    private static String buildMarkdown(Map<String, Object> report) {
        Map<String, Object> gates = report.get("gates") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Map<String, Object> recallAtK = report.get("recall_at_k") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Map<String, Object> latency = report.get("latency_ms") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        List<String> lines = new ArrayList<>();
        lines.add("# RAG 评测报告 — " + report.getOrDefault("run_at", report.get("date")));
        lines.add("");
        lines.add("> schema_version=" + report.get("schema_version")
                + " · suite_key=" + report.get("suite_key")
                + " · strategy=" + report.get("strategy")
                + " · " + report.get("query_count") + " queries"
                + " · min_score=" + report.get("min_score")
                + " · run=" + report.get("run_tag"));
        lines.add("");
        lines.add("## 汇总指标");
        lines.add("");
        lines.add("| 指标 | 值 | 生产门禁 |");
        lines.add("|------|-----|----------|");
        lines.add("| Recall@3 | " + recallAtK.get("3") + " | ≥ " + gates.get("recallAt3Min") + " |");
        lines.add("| Recall@5 | " + recallAtK.get("5") + " | ≥ " + gates.get("recallAt5Min") + " |");
        lines.add("| MRR | " + report.get("mrr") + " | ≥ " + gates.get("mrrMin") + " |");
        lines.add("| 正例 EmptyRate | " + report.get("empty_rate_positive") + " | = 0 |");
        lines.add("| 负例 EmptyRate | " + report.get("empty_rate_negative") + " | ≥ "
                + gates.get("emptyRateNegativeMin") + " |");
        lines.add("| P50 延迟 (ms) | " + latency.get("p50") + " | — |");
        lines.add("| P95 延迟 (ms) | " + latency.get("p95") + " | ≤ " + gates.get("latencyP95MsMax") + " |");
        lines.add("");
        return String.join("\n", lines);
    }
}
