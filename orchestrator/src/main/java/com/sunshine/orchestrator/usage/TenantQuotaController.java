package com.sunshine.orchestrator.usage;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.usage.entity.TenantQuotaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 租户配额端点（phase5 5.2.4）：管理 CRUD + 请求前校验（llm-gateway 调用）。 */
@RestController
@RequestMapping("/api/usage/quota")
@RequiredArgsConstructor
public class TenantQuotaController {

    private final TenantQuotaService quotaService;

    @GetMapping
    public R<List<TenantQuotaEntity>> list() {
        return R.ok(quotaService.list());
    }

    @PostMapping
    public R<TenantQuotaEntity> upsert(@RequestBody TenantQuotaEntity body) {
        return R.ok(quotaService.upsert(body));
    }

    @DeleteMapping("/{tenantId}")
    public R<Void> delete(@PathVariable String tenantId) {
        quotaService.delete(tenantId);
        return R.ok();
    }

    @GetMapping("/check")
    public R<Map<String, Object>> check(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String model) {
        return R.ok(quotaService.check(tenantId, model));
    }
}
