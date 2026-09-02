package com.sunshine.workflow.service;

import com.sunshine.workflow.config.WorkflowStudioProperties;
import com.sunshine.workflow.dto.WorkflowCatalogDefaults;
import com.sunshine.workflow.dto.WorkflowNodeDefaultsResponse;
import com.sunshine.workflow.dto.WorkflowNodeParamDefaults;
import com.sunshine.workflow.dto.WorkflowNodeRetryDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkflowNodeDefaultsService {

    private final WorkflowStudioProperties studioProperties;

    public WorkflowNodeDefaultsResponse getNodeDefaults() {
        WorkflowStudioProperties.NodeDefaults cfg = studioProperties.getNodeDefaults();
        WorkflowStudioProperties.RetryDefaults base = cfg.getDefaults() != null
                ? cfg.getDefaults()
                : new WorkflowStudioProperties.RetryDefaults();
        WorkflowNodeRetryDefaults defaults = toResolved(base, null);
        Map<String, WorkflowNodeRetryDefaults> byType = new LinkedHashMap<>();
        if (cfg.getByType() != null) {
            cfg.getByType().forEach((type, override) ->
                    byType.put(type, toResolved(base, override)));
        }
        return new WorkflowNodeDefaultsResponse(
                defaults,
                Map.copyOf(byType),
                base.getBackoffMultiplier() > 0 ? base.getBackoffMultiplier() : 2.0,
                base.getRetryOnErrorClass() != null ? List.copyOf(base.getRetryOnErrorClass()) : List.of(),
                toCatalogDefaults(studioProperties.getCatalogDefaults()),
                toNodeParamDefaults(studioProperties.getNodeParamDefaults()));
    }

    private static WorkflowCatalogDefaults toCatalogDefaults(WorkflowStudioProperties.CatalogDefaults cfg) {
        if (cfg == null) {
            return new WorkflowCatalogDefaults("将按「{displayName}」流程处理");
        }
        String intentAfter = StringUtils.hasText(cfg.getIntentAfter())
                ? cfg.getIntentAfter().strip()
                : "将按「{displayName}」流程处理";
        return new WorkflowCatalogDefaults(intentAfter);
    }

    private static Map<String, WorkflowNodeParamDefaults> toNodeParamDefaults(
            Map<String, WorkflowStudioProperties.NodeParamDefaults> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of(
                    "rag", new WorkflowNodeParamDefaults(3, "（会话默认）", null),
                    "agent", new WorkflowNodeParamDefaults(null, "（会话默认）", 8));
        }
        Map<String, WorkflowNodeParamDefaults> out = new LinkedHashMap<>();
        raw.forEach((type, cfg) -> {
            if (cfg == null) {
                return;
            }
            out.put(type, new WorkflowNodeParamDefaults(
                    cfg.getTopK(),
                    StringUtils.hasText(cfg.getKbIdEmptyLabel()) ? cfg.getKbIdEmptyLabel().strip() : null,
                    cfg.getMaxIters()));
        });
        return Map.copyOf(out);
    }

    public WorkflowNodeRetryDefaults resolveForType(String nodeType) {
        WorkflowNodeDefaultsResponse all = getNodeDefaults();
        if (StringUtils.hasText(nodeType) && all.byType().containsKey(nodeType.strip())) {
            return all.byType().get(nodeType.strip());
        }
        return all.defaults();
    }

    private static WorkflowNodeRetryDefaults toResolved(
            WorkflowStudioProperties.RetryDefaults base,
            WorkflowStudioProperties.TypeRetryDefaults override) {
        int maxAttempts = base.getMaxAttempts();
        long backoffMs = base.getBackoffMs();
        String onFailure = base.getOnFailure();
        if (override != null) {
            if (override.getMaxAttempts() != null && override.getMaxAttempts() > 0) {
                maxAttempts = override.getMaxAttempts();
            }
            if (override.getBackoffMs() != null && override.getBackoffMs() > 0) {
                backoffMs = override.getBackoffMs();
            }
            if (StringUtils.hasText(override.getOnFailure())) {
                onFailure = override.getOnFailure().strip();
            }
        }
        return new WorkflowNodeRetryDefaults(maxAttempts, backoffMs, onFailure);
    }
}
