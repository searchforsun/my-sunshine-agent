package com.sunshine.rag.admin.eval;

import com.sunshine.rag.admin.eval.dto.EvalSuiteCreateRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuiteDetail;
import com.sunshine.rag.admin.eval.dto.EvalSuiteQueryRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuiteSummary;
import com.sunshine.rag.admin.eval.dto.EvalSuiteUpdateRequest;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag/admin/eval/suites")
@RequiredArgsConstructor
public class EvalSuiteAdminController {

    private final EvalSuiteService evalSuiteService;

    @GetMapping
    public R<List<EvalSuiteSummary>> list(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return R.ok(evalSuiteService.list(tenantId));
    }

    @GetMapping("/{suiteKey}")
    public R<EvalSuiteDetail> get(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String suiteKey) {
        return R.ok(evalSuiteService.get(tenantId, suiteKey));
    }

    @PostMapping
    public R<EvalSuiteDetail> create(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestBody EvalSuiteCreateRequest request) {
        return R.ok(evalSuiteService.create(tenantId, request));
    }

    @PutMapping("/{suiteKey}")
    public R<EvalSuiteDetail> update(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String suiteKey,
            @RequestBody EvalSuiteUpdateRequest request) {
        return R.ok(evalSuiteService.update(tenantId, suiteKey, request));
    }

    @DeleteMapping("/{suiteKey}")
    public R<Void> delete(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String suiteKey) {
        evalSuiteService.delete(tenantId, suiteKey);
        return R.ok(null);
    }

    @PostMapping("/{suiteKey}/queries")
    public R<EvalSuiteDetail> mutateQuery(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String suiteKey,
            @RequestBody EvalSuiteQueryRequest request) {
        return R.ok(evalSuiteService.mutateQuery(tenantId, suiteKey, request));
    }

    @PostMapping("/kb-custom/ensure")
    public R<EvalSuiteDetail> ensureKbCustom(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestParam String kbId,
            @RequestParam(required = false) String displayName) {
        return R.ok(evalSuiteService.ensureKbCustomSuite(tenantId, kbId, displayName));
    }
}
