package com.sunshine.rag.admin.config;

import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rag/admin/kbs/{kbId}/config")
@RequiredArgsConstructor
public class KbConfigOverrideController {

    private final KbConfigOverrideService kbConfigOverrideService;

    @GetMapping("/effective")
    public R<EffectiveRagConfig> effective(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId) {
        return R.ok(kbConfigOverrideService.getEffective(tenantId, kbId));
    }

    @PutMapping("/override")
    public R<EffectiveRagConfig> putOverride(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @RequestBody Map<String, Object> patch) {
        return R.ok(kbConfigOverrideService.putOverride(tenantId, kbId, patch));
    }

    @DeleteMapping("/override/{field}")
    public R<EffectiveRagConfig> deleteField(
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @PathVariable String kbId,
            @PathVariable String field) {
        return R.ok(kbConfigOverrideService.deleteField(tenantId, kbId, field));
    }
}
