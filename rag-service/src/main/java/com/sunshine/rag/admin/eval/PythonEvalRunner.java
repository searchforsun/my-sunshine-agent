package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagAdminProperties;
import com.sunshine.rag.entity.EvalSuiteEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonEvalRunner {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final EvalSuiteService evalSuiteService;
    private final RagAdminProperties adminProperties;
    private final ObjectMapper objectMapper;

    public Map<String, Object> run(EvalSuiteEntity suite, String tenantId, String kbId, long jobId) {
        if (!"python".equals(suite.getKind())) {
            throw new IllegalArgumentException("suite 非 python 类型: " + suite.getSuiteKey());
        }
        Path scriptPath;
        try {
            scriptPath = materializeScript(suite, jobId);
        } catch (Exception e) {
            throw new IllegalStateException("python eval 脚本落盘失败: " + e.getMessage(), e);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder("python3", scriptPath.toString());
            builder.redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.clear();
            env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
            env.put("RAG_EVAL_INTERNAL_URL", "http://127.0.0.1:8400/api/rag/admin/eval/internal/run");
            env.put("RAG_EVAL_ADMIN_TOKEN", adminProperties.getToken());
            env.put("RAG_EVAL_TENANT_ID", tenantId);
            env.put("RAG_EVAL_KB_ID", kbId);
            env.put("RAG_EVAL_JOB_ID", String.valueOf(jobId));
            env.put("PYTHONUNBUFFERED", "1");
            Process process = builder.start();
            String output = readProcessOutput(process);
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("python eval 超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("python eval 失败 exit=" + process.exitValue() + " output=" + output);
            }
            return parseOutput(output);
        } catch (Exception e) {
            throw new IllegalStateException("python eval 执行失败: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(scriptPath);
            } catch (Exception ignored) {
                // 临时脚本清理失败不影响主流程
            }
        }
    }

    private Path materializeScript(EvalSuiteEntity suite, long jobId) throws Exception {
        String content = evalSuiteService.loadPythonContent(suite);
        Path dir = Files.createTempDirectory("rag-eval-py-" + jobId + "-");
        Path script = dir.resolve("suite.py");
        Files.writeString(script, content, StandardCharsets.UTF_8);
        return script;
    }

    private static String readProcessOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOutput(String output) throws Exception {
        String json = extractJson(output);
        Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("metrics", parsed.getOrDefault("metrics", Map.of()));
        report.put("failedSamples", parsed.getOrDefault("failedSamples", List.of()));
        report.put("query_count", countFailedSamples(parsed));
        if (parsed.get("metrics") instanceof Map<?, ?> metrics) {
            Object recall = ((Map<String, Object>) metrics).get("recall_at_5");
            if (recall instanceof Number number) {
                report.put("recall_at_k", Map.of("5", number.doubleValue()));
                report.put("mrr", ((Map<String, Object>) metrics).getOrDefault("mrr", 0.0));
            }
        }
        report.put("badcases", Map.of("positive_miss", parsed.getOrDefault("failedSamples", List.of())));
        return report;
    }

    private static int countFailedSamples(Map<String, Object> parsed) {
        Object failed = parsed.get("failedSamples");
        if (failed instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private static String extractJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return output.substring(start, end + 1);
        }
        throw new IllegalStateException("python eval 输出非 JSON: " + output);
    }
}
