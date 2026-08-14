package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.SkillCatalogClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 缓存 skill-manager catalog — 摘要常驻，正文按需拉取 */
@Slf4j
@Service
@RefreshScope
public class SkillCatalogService {

    private final SkillCatalogClient catalogClient;
    private volatile Map<String, SkillCatalogIndexEntry> indexEntries = Map.of();
    private final Map<String, SkillCatalogEntry> detailCache = new ConcurrentHashMap<>();

    public SkillCatalogService(SkillCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, SkillCatalogIndexEntry> merged = new LinkedHashMap<>();
        for (SkillCatalogIndexEntry entry : catalogClient.fetchCatalogIndex()) {
            if (entry.id() != null) {
                merged.put(entry.id(), entry);
            }
        }
        this.indexEntries = Map.copyOf(merged);
        this.detailCache.clear();
        log.info("[SkillCatalogService] index loaded: {}", String.join(", ", indexEntries.keySet()));
    }

    public List<SkillCatalogIndexEntry> indexEntries() {
        return List.copyOf(indexEntries.values());
    }

    public Optional<SkillCatalogIndexEntry> findIndex(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(indexEntries.get(skillId.strip()));
    }

    public Optional<SkillCatalogEntry> find(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return Optional.empty();
        }
        String id = skillId.strip();
        SkillCatalogEntry cached = detailCache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<SkillCatalogEntry> loaded = catalogClient.fetchSkillDetail(id);
        loaded.ifPresent(entry -> detailCache.put(id, entry));
        return loaded;
    }

    public String overlayOrEmpty(String skillId) {
        return find(skillId).map(SkillCatalogEntry::systemOverlay).orElse("");
    }

    /** skill 绑定的工具 Catalog ID 列表（经详情缓存） */
    public List<String> toolIds(String skillId) {
        return find(skillId).map(SkillCatalogEntry::toolIds).orElse(List.of());
    }

    /** L3 意图分类器 — Skill 目录（含 sandbox 能力），按会话 kind 过滤（保留 all + 同 kind） */
    public String renderForClassifier(String sessionKind) {
        return renderForClassifier(sessionKind, false);
    }

    /** 兼容旧调用（无 kind 上下文 → 全量） */
    public String renderForClassifier() {
        return renderForClassifier(null, true);
    }

    private String renderForClassifier(String sessionKind, boolean includeAll) {
        if (indexEntries().isEmpty()) {
            return "(无 skill 目录)";
        }
        return indexEntries().stream()
                .filter(SkillCatalogIndexEntry::enabled)
                .filter(e -> includeAll || ResourceKindFilter.matches(e.kind(), sessionKind))
                .map(e -> "- **" + e.id() + "**: " + e.displayName()
                        + " | sandbox=" + e.sandbox()
                        + (StringUtils.hasText(e.description()) ? " — " + e.description() : ""))
                .collect(Collectors.joining("\n"));
    }

    public String renderIntoClassifier(String classifierPrompt, String sessionKind) {
        if (!StringUtils.hasText(classifierPrompt)) {
            return classifierPrompt;
        }
        return classifierPrompt.replace("{{skill-catalog}}", renderForClassifier(sessionKind));
    }

    public String renderIntoClassifier(String classifierPrompt) {
        if (!StringUtils.hasText(classifierPrompt)) {
            return classifierPrompt;
        }
        return classifierPrompt.replace("{{skill-catalog}}", renderForClassifier());
    }

    /** 校验 plan.params.skill 是否在 catalog 内；未知 id 剥离 */
    public ExecutionPlan sanitizeSkillPlan(ExecutionPlan plan) {
        if (plan == null || plan.params() == null) {
            return plan;
        }
        String skillId = plan.params().get(SkillBindingOutcome.PARAM_SKILL);
        if (!StringUtils.hasText(skillId)) {
            return plan;
        }
        if (findIndex(skillId.strip()).filter(SkillCatalogIndexEntry::enabled).isPresent()) {
            return plan;
        }
        Map<String, String> params = new LinkedHashMap<>(plan.params());
        params.remove(SkillBindingOutcome.PARAM_SKILL);
        log.warn("[SkillCatalogService] unknown skillId={}, stripped from plan", skillId);
        return new ExecutionPlan(plan.mode(), plan.workflowId(), params, plan.reason(), plan.ruleId(),
                plan.routingTraces());
    }
}
