package com.sunshine.rag.admin.eval;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.eval.dto.EvalInternalRunRequest;
import com.sunshine.rag.admin.eval.dto.EvalJobStatus;
import com.sunshine.rag.admin.eval.dto.EvalJobSummary;
import com.sunshine.rag.admin.eval.dto.EvalReportView;
import com.sunshine.rag.admin.eval.dto.EvalRunRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuggestRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import com.sunshine.rag.admin.config.ConfigResolveMode;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.pipeline.PipelineSearchRequest;
import com.sunshine.rag.pipeline.PipelineSearchResult;
import com.sunshine.rag.pipeline.KnowledgeRetrievalPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag/admin/eval")
@RequiredArgsConstructor
public class EvalAdminController {

    private final EvaluateService evaluateService;
    private final SuggestService suggestService;
    private final KnowledgeRetrievalPipeline pipeline;

    @PostMapping("/run")
    public R<EvalJobStatus> run(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestBody EvalRunRequest request) {
        return R.ok(evaluateService.submitRun(tenantId, request));
    }

    @GetMapping("/jobs/{jobId}")
    public R<EvalJobStatus> job(@PathVariable long jobId) {
        return R.ok(evaluateService.getJob(jobId));
    }

    @GetMapping("/reports/{reportId}")
    public R<EvalReportView> report(@PathVariable long reportId) {
        return R.ok(evaluateService.getReport(reportId));
    }

    @GetMapping("/jobs")
    public R<List<EvalJobSummary>> listJobs(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestParam String kbId,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(evaluateService.listJobs(tenantId, kbId, limit));
    }

    @PostMapping("/suggest")
    public R<EvalSuggestResult> suggest(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestBody EvalSuggestRequest request) {
        return R.ok(suggestService.suggest(tenantId, request));
    }

    /** Python eval 脚本受信内部端点（localhost + X-Admin-Token） */
    @PostMapping("/internal/run")
    public R<Map<String, Object>> internalRun(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestBody EvalInternalRunRequest request) {
        String kbId = request.kbId() != null && !request.kbId().isBlank() ? request.kbId().strip() : "default";
        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 5;
        ConfigResolveMode mode = ConfigResolveMode.parse(
                request.configMode() != null ? request.configMode() : "published");
        EffectiveRagConfig config = evaluateService.resolveEvalConfig(
                tenantId, kbId, mode, request.configVersionId());
        PipelineSearchRequest searchRequest = PipelineSearchRequest.of(
                request.query(), topK, tenantId, kbId, request.strategy(), true, false);
        PipelineSearchResult result = pipeline.searchWithConfig(searchRequest, config).block();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", request.query());
        body.put("results", result != null ? result.results() : java.util.List.of());
        return R.ok(body);
    }
}
