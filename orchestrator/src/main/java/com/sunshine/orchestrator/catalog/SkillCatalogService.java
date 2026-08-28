package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.SkillCatalogClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;

import java.util.Comparator;
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
        for (SkillCatalogIndexEntry entry : catalogClient.fetchCatalogIndex(null)) {
            if (entry.id() != null) {
                merged.put(entry.id(), entry);
            }
        }
        this.indexEntries = Map.copyOf(merged);
        this.detailCache.clear();
        log.info("[SkillCatalogService] index loaded: {}", String.join(", ", indexEntries.keySet()));
    }

    public List<SkillCatalogIndexEntry> indexEntries() {
        // Map.copyOf 不保证迭代顺序：目录/分类器依赖稳定顺序，按 id 升序
        return indexEntries.values().stream()
                .sorted(Comparator.comparing(SkillCatalogIndexEntry::id))
                .toList();
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

    /**
     * 可发现目录（名+描述）：enabled + 按会话 kind 过滤（保留 all + 同 kind） + 租户可见，剔除已触发项。
     * 仅用于 Prompt 目录摘要层，正文按触发注入（skill-sticky S-D）。
     */
    public List<SkillCatalogIndexEntry> discoverableForPrompt(
            String sessionKind, List<String> triggeredSkillIds, String tenantId) {
        List<String> triggered = triggeredSkillIds != null ? triggeredSkillIds : List.of();
        String effectiveTenant = TenantVisibility.normalize(tenantId);
        return indexEntries().stream()
                .filter(SkillCatalogIndexEntry::enabled)
                .filter(e -> ResourceKindFilter.matches(e.kind(), sessionKind))
                .filter(e -> TenantVisibility.visible(e.tenantId(), effectiveTenant))
                .filter(e -> !triggered.contains(e.id()))
                .toList();
    }

    /**
     * 可发现目录渲染（名+描述，不灌正文）：模板占位 {skills}，Top-N 上限防前缀膨胀。
     * 候选集（S-C）提权置顶并标记「可动态加载」；无候选时退化为原渲染。
     * 超过上限时给出「更多经 / 或检索」提示，对齐 spec §8 目录过长对策。
     */
    public String renderDiscoverableForPrompt(
            String sessionKind, List<String> triggeredSkillIds, int topN, String tenantId) {
        return renderDiscoverableForPrompt(sessionKind, triggeredSkillIds, List.of(), topN, tenantId);
    }

    public String renderDiscoverableForPrompt(
            String sessionKind, List<String> triggeredSkillIds, List<String> candidateSkillIds,
            int topN, String tenantId) {
        List<SkillCatalogIndexEntry> entries = discoverableForPrompt(sessionKind, triggeredSkillIds, tenantId);
        List<SkillCatalogIndexEntry> ordered = promoteCandidates(entries, candidateSkillIds);
        if (ordered.isEmpty()) {
            return "";
        }
        java.util.Set<String> candidates = candidateSkillIds != null
                ? new java.util.LinkedHashSet<>(candidateSkillIds) : java.util.Set.of();
        int limit = Math.max(1, topN);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i >= limit) {
                sb.append("- …还有 ").append(ordered.size() - limit)
                        .append(" 项：输入 /技能名 或描述需求以加载\n");
                break;
            }
            SkillCatalogIndexEntry e = ordered.get(i);
            sb.append("- **").append(e.id()).append("**");
            if (StringUtils.hasText(e.displayName())) {
                sb.append(" ").append(e.displayName().strip());
            }
            if (StringUtils.hasText(e.description())) {
                sb.append(" — ").append(e.description().strip());
            }
            if (candidates.contains(e.id())) {
                sb.append("（可动态加载）");
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    /** 候选 skill 提权置顶（保持相对顺序），其余按原顺序跟随；仅存在于可见集内的候选生效 */
    private static List<SkillCatalogIndexEntry> promoteCandidates(
            List<SkillCatalogIndexEntry> entries, List<String> candidateSkillIds) {
        if (candidateSkillIds == null || candidateSkillIds.isEmpty() || entries.isEmpty()) {
            return entries;
        }
        java.util.Map<String, SkillCatalogIndexEntry> byId = new java.util.LinkedHashMap<>();
        for (SkillCatalogIndexEntry e : entries) {
            byId.put(e.id(), e);
        }
        List<SkillCatalogIndexEntry> ordered = new java.util.ArrayList<>();
        java.util.Set<String> added = new java.util.LinkedHashSet<>();
        for (String id : candidateSkillIds) {
            SkillCatalogIndexEntry e = byId.get(id);
            if (e != null && added.add(id)) {
                ordered.add(e);
            }
        }
        for (SkillCatalogIndexEntry e : entries) {
            if (added.add(e.id())) {
                ordered.add(e);
            }
        }
        return ordered;
    }

    /** L3 意图分类器 — Skill 目录（含 sandbox 能力），按会话 kind + 租户过滤（保留 all + 同 kind） */
    public String renderForClassifier(String sessionKind, String tenantId) {
        if (indexEntries().isEmpty()) {
            return "(无 skill 目录)";
        }
        String effectiveTenant = TenantVisibility.normalize(tenantId);
        return indexEntries().stream()
                .filter(SkillCatalogIndexEntry::enabled)
                .filter(e -> ResourceKindFilter.matches(e.kind(), sessionKind))
                .filter(e -> TenantVisibility.visible(e.tenantId(), effectiveTenant))
                .map(e -> "- **" + e.id() + "**: " + e.displayName()
                        + " | sandbox=" + e.sandbox()
                        + (StringUtils.hasText(e.description()) ? " — " + e.description() : ""))
                .collect(Collectors.joining("\n"));
    }

    public String renderIntoClassifier(String classifierPrompt, String sessionKind, String tenantId) {
        if (!StringUtils.hasText(classifierPrompt)) {
            return classifierPrompt;
        }
        return classifierPrompt.replace("{{skill-catalog}}", renderForClassifier(sessionKind, tenantId));
    }

    /** 校验 plan.params.skill / skillIds 是否在 catalog 内（enabled + 租户可见）；未知 id 剥离 */
    public ExecutionPlan sanitizeSkillPlan(ExecutionPlan plan, String tenantId) {
        if (plan == null || plan.params() == null) {
            return plan;
        }
        String effectiveTenant = TenantVisibility.normalize(tenantId);
        Map<String, String> params = new LinkedHashMap<>(plan.params());
        boolean changed = false;
        String skillId = params.get(SkillBindingOutcome.PARAM_SKILL);
        if (StringUtils.hasText(skillId)
                && findIndex(skillId.strip()).filter(SkillCatalogIndexEntry::enabled)
                        .filter(e -> TenantVisibility.visible(e.tenantId(), effectiveTenant))
                        .isEmpty()) {
            params.remove(SkillBindingOutcome.PARAM_SKILL);
            changed = true;
        }
        String skillIdsRaw = params.get(PARAM_SKILL_IDS);
        if (StringUtils.hasText(skillIdsRaw)) {
            List<String> kept = new java.util.ArrayList<>();
            for (String id : skillIdsRaw.split(",")) {
                String sid = id.strip();
                if (sid.isEmpty()) {
                    continue;
                }
                if (findIndex(sid).filter(SkillCatalogIndexEntry::enabled)
                        .filter(e -> TenantVisibility.visible(e.tenantId(), effectiveTenant))
                        .isPresent()) {
                    kept.add(sid);
                } else {
                    changed = true;
                    log.warn("[SkillCatalogService] unknown skillId={}, stripped from plan", sid);
                }
            }
            if (kept.isEmpty()) {
                params.remove(PARAM_SKILL_IDS);
            } else {
                params.put(PARAM_SKILL_IDS, String.join(",", kept));
            }
        }
        if (!changed) {
            return plan;
        }
        log.warn("[SkillCatalogService] sanitize plan params: skillIds pruned");
        return new ExecutionPlan(plan.mode(), plan.workflowId(), params, plan.reason(), plan.ruleId(),
                plan.routingTraces());
    }

    private static final String PARAM_SKILL_IDS = "skillIds";
}
