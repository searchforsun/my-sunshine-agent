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

    /** skill 绑定的工具 Catalog ID 列表（经详情缓存） */
    public List<String> toolIds(String skillId) {
        return find(skillId).map(SkillCatalogEntry::toolIds).orElse(List.of());
    }

    /**
     * 可发现目录（名+描述）：enabled + 按会话 kind 过滤（保留 all + 同 kind） + 租户可见。
     * 目录是 Prompt 前缀稳定区，与触发集解耦——不剔除、不标注已触发项，否则触发集变化会
     * 位移前缀字节、击穿 KV 缓存（skill-sticky C1）。已触发正文由尾部 <skills_referenced> 信封承载。
     */
    public List<SkillCatalogIndexEntry> discoverableForPrompt(String sessionKind, String tenantId) {
        String effectiveTenant = TenantVisibility.normalize(tenantId);
        return indexEntries().stream()
                .filter(SkillCatalogIndexEntry::enabled)
                .filter(e -> ResourceKindFilter.matches(e.kind(), sessionKind))
                .filter(e -> TenantVisibility.visible(e.tenantId(), effectiveTenant))
                .toList();
    }

    /**
     * 可发现目录渲染（名+描述，不灌正文）：模板占位 {skills}，Top-N 上限防前缀膨胀。
     * 输出仅由 (kind, tenant, 技能目录本身) 决定——轮间字节稳定是 C1 前缀稳定不变量，
     * 任何逐消息变化的内容（候选集提权等）不得进入目录。
     * 超过上限时给出「更多经 / 或检索」提示，对齐 spec §8 目录过长对策。
     */
    public String renderDiscoverableForPrompt(String sessionKind, int topN, String tenantId) {
        List<SkillCatalogIndexEntry> entries = discoverableForPrompt(sessionKind, tenantId);
        if (entries.isEmpty()) {
            return "";
        }
        int limit = Math.max(1, topN);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i >= limit) {
                sb.append("- …还有 ").append(entries.size() - limit)
                        .append(" 项：输入 /技能名 或描述需求以加载\n");
                break;
            }
            SkillCatalogIndexEntry e = entries.get(i);
            sb.append("- **").append(e.id()).append("**");
            if (StringUtils.hasText(e.displayName())) {
                sb.append(" ").append(e.displayName().strip());
            }
            if (StringUtils.hasText(e.description())) {
                sb.append(" — ").append(e.description().strip());
            }
            sb.append('\n');
        }
        return sb.toString().strip();
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
