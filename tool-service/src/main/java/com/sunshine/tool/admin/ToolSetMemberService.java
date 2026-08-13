package com.sunshine.tool.admin;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.tool.ToolIds;
import com.sunshine.common.tool.admin.ToolSetMemberAddItem;
import com.sunshine.common.tool.admin.ToolSetMemberAddRequest;
import com.sunshine.common.tool.admin.ToolSetMemberAddResult;
import com.sunshine.common.tool.admin.ToolSetMemberCriticalPatchRequest;
import com.sunshine.common.tool.admin.ToolSetMemberItemResponse;
import com.sunshine.common.tool.admin.ToolSetMemberReject;
import com.sunshine.common.tool.admin.ToolSetMemberRemoveRequest;
import com.sunshine.common.tool.admin.ToolSetMembersPageResponse;
import com.sunshine.common.tool.admin.ToolSetPickerGroup;
import com.sunshine.common.tool.admin.ToolSetPickerResponse;
import com.sunshine.common.tool.admin.ToolSetPickerToolItem;
import com.sunshine.common.tool.admin.ToolSetToolIdsResponse;
import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.entity.SdkApplicationEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.entity.ToolSetEntity;
import com.sunshine.tool.entity.ToolSetMemberEntity;
import com.sunshine.tool.event.ToolCatalogChangePublisher;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.McpServerRepository;
import com.sunshine.tool.repo.SdkApplicationRepository;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import com.sunshine.tool.repo.ToolSetMemberRepository;
import com.sunshine.tool.repo.ToolSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ToolSetMemberService {

    private final ToolSetRepository toolSetRepository;
    private final ToolSetMemberRepository toolSetMemberRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final SdkApplicationRepository sdkApplicationRepository;
    private final McpServerRepository mcpServerRepository;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    public ToolSetMembersPageResponse pageMembers(
            ToolSetKind kind, String tenantId, int page, int size, String query) {
        String effectiveTenant = normalizeTenant(tenantId);
        List<ToolSetMemberItemResponse> all = findSet(kind, tenantId)
                .map(set -> buildMemberItems(set.getId(), effectiveTenant))
                .orElse(List.of());
        String q = query == null ? "" : query.strip().toLowerCase();
        if (!q.isEmpty()) {
            all = all.stream().filter(item -> matchesQuery(item, q)).toList();
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int from = (safePage - 1) * safeSize;
        List<ToolSetMemberItemResponse> slice = from >= all.size()
                ? List.of()
                : all.subList(from, Math.min(from + safeSize, all.size()));
        return new ToolSetMembersPageResponse(safePage, safeSize, all.size(), slice);
    }

    public ToolSetPickerResponse picker(ToolSetKind kind, String tenantId, String query) {
        Set<String> memberIds = findSet(kind, tenantId)
                .map(set -> memberToolIds(set.getId()))
                .orElse(Set.of());
        String effectiveTenant = normalizeTenant(tenantId);
        String q = query == null ? "" : query.strip().toLowerCase();
        Map<String, String> sourceLabels = loadSourceLabels();
        Map<String, List<ToolDefinitionEntity>> grouped = new LinkedHashMap<>();
        for (ToolDefinitionEntity tool : toolDefinitionRepository.findVisibleForTenant(effectiveTenant, true)) {
            if (memberIds.contains(tool.getId())) {
                continue;
            }
            if (!q.isEmpty() && !matchesToolQuery(tool, sourceLabels, q)) {
                continue;
            }
            String key = tool.getSource() + "\0" + tool.getSourceRef();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(tool);
        }
        List<ToolSetPickerGroup> groups = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    String[] parts = entry.getKey().split("\0", 2);
                    String source = parts[0];
                    String sourceRef = parts.length > 1 ? parts[1] : "";
                    String title = formatSourceTitle(source, sourceRef, sourceLabels);
                    List<ToolSetPickerToolItem> tools = entry.getValue().stream()
                            .sorted(Comparator.comparing(ToolDefinitionEntity::getId))
                            .map(t -> new ToolSetPickerToolItem(
                                    t.getId(),
                                    t.getDisplayName(),
                                    t.getSideEffect()))
                            .toList();
                    return new ToolSetPickerGroup(source, sourceRef, title, tools);
                })
                .filter(g -> !g.tools().isEmpty())
                .toList();
        return new ToolSetPickerResponse(groups);
    }

    @Transactional
    public ToolSetMemberAddResult addMembers(ToolSetKind kind, String tenantId, ToolSetMemberAddRequest request) {
        WritableSet writable = resolveWritableSet(kind, tenantId);
        List<ToolSetMemberAddItem> items = request != null && request.items() != null ? request.items() : List.of();
        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ToolSetMemberReject> rejected = new ArrayList<>();
        int nextOrder = nextSortOrder(writable.set().getId());
        String effectiveTenant = normalizeTenant(tenantId);
        for (ToolSetMemberAddItem item : items) {
            if (item == null || !StringUtils.hasText(item.toolId())) {
                continue;
            }
            String toolId = item.toolId().strip();
            if (!ToolIds.isValid(toolId)) {
                rejected.add(new ToolSetMemberReject(toolId, "invalid_id"));
                continue;
            }
            if (toolSetMemberRepository.existsBySetIdAndToolId(writable.set().getId(), toolId)) {
                skipped.add(toolId);
                continue;
            }
            Optional<ToolDefinitionEntity> toolOpt = toolDefinitionRepository.findVisibleById(toolId, effectiveTenant);
            if (toolOpt.isEmpty()) {
                rejected.add(new ToolSetMemberReject(toolId, "not_found"));
                continue;
            }
            ToolDefinitionEntity tool = toolOpt.get();
            if (!tool.isEnabled() || !tool.isIdValid()) {
                rejected.add(new ToolSetMemberReject(toolId, "not_enabled"));
                continue;
            }
            ToolSetMemberEntity member = new ToolSetMemberEntity();
            member.setSetId(writable.set().getId());
            member.setToolId(toolId);
            member.setSortOrder(nextOrder++);
            member.setCritical(kind == ToolSetKind.TASK_DEFAULT && Boolean.TRUE.equals(item.critical()));
            toolSetMemberRepository.save(member);
            added.add(toolId);
        }
        if (!added.isEmpty()) {
            touchSet(writable.set());
            publish(tenantId);
        }
        return new ToolSetMemberAddResult(added, skipped, rejected);
    }

    @Transactional
    public void removeMembers(ToolSetKind kind, String tenantId, ToolSetMemberRemoveRequest request) {
        List<String> toolIds = request != null && request.toolIds() != null ? request.toolIds() : List.of();
        if (toolIds.isEmpty()) {
            return;
        }
        WritableSet writable = resolveWritableSet(kind, tenantId);
        List<String> normalized = toolIds.stream()
                .filter(StringUtils::hasText)
                .map(String::strip)
                .distinct()
                .toList();
        toolSetMemberRepository.deleteBySetIdAndToolIdIn(writable.set().getId(), normalized);
        touchSet(writable.set());
        publish(tenantId);
    }

    @Transactional
    public void patchCritical(String tenantId, String toolId, ToolSetMemberCriticalPatchRequest request) {
        if (!StringUtils.hasText(toolId)) {
            throw new BizException(ToolErrorCode.TOOL_ID_INVALID);
        }
        WritableSet writable = resolveWritableSet(ToolSetKind.TASK_DEFAULT, tenantId);
        ToolSetMemberEntity member = toolSetMemberRepository
                .findBySetIdAndToolId(writable.set().getId(), toolId.strip())
                .orElseThrow(() -> new BizException(ToolErrorCode.TOOL_SET_MEMBER_NOT_FOUND));
        member.setCritical(request != null && request.critical());
        toolSetMemberRepository.save(member);
        touchSet(writable.set());
        publish(tenantId);
    }

    /**
     * Runtime 读：优先新 set 成员，再并入 legacy set 中尚未出现的 toolId（去重）。
     * Admin 写路径不经此方法，只碰新 set。
     */
    public ToolSetToolIdsResponse toolIds(ToolSetKind kind, String tenantId) {
        List<ToolSetMemberEntity> primary = findSet(kind, tenantId)
                .map(set -> toolSetMemberRepository.findBySetIdOrderBySortOrderAsc(set.getId()))
                .orElse(List.of());
        List<ToolSetMemberEntity> legacy = findLegacySet(kind, tenantId)
                .map(set -> toolSetMemberRepository.findBySetIdOrderBySortOrderAsc(set.getId()))
                .orElse(List.of());
        LinkedHashMap<String, Boolean> merged = new LinkedHashMap<>();
        for (ToolSetMemberEntity member : primary) {
            merged.put(member.getToolId(), member.isCritical());
        }
        for (ToolSetMemberEntity member : legacy) {
            merged.putIfAbsent(member.getToolId(), member.isCritical());
        }
        List<String> toolIds = List.copyOf(merged.keySet());
        List<String> criticalIds = kind == ToolSetKind.TASK_DEFAULT
                ? merged.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList()
                : List.of();
        return new ToolSetToolIdsResponse(toolIds, criticalIds);
    }

    private List<ToolSetMemberItemResponse> buildMemberItems(String setId, String tenantId) {
        Map<String, String> sourceLabels = loadSourceLabels();
        List<ToolSetMemberItemResponse> items = new ArrayList<>();
        for (ToolSetMemberEntity member : toolSetMemberRepository.findBySetIdOrderBySortOrderAsc(setId)) {
            toolDefinitionRepository.findVisibleById(member.getToolId(), tenantId).ifPresent(tool -> items.add(
                    new ToolSetMemberItemResponse(
                            tool.getId(),
                            tool.getDisplayName(),
                            tool.getDescription() != null ? tool.getDescription() : "",
                            tool.getSource(),
                            tool.getSourceRef(),
                            formatSourceTitle(tool.getSource(), tool.getSourceRef(), sourceLabels),
                            tool.getSideEffect(),
                            member.isCritical(),
                            member.getSortOrder())));
        }
        return items;
    }

    private Set<String> memberToolIds(String setId) {
        return toolSetMemberRepository.findBySetIdOrderBySortOrderAsc(setId).stream()
                .map(ToolSetMemberEntity::getToolId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private int nextSortOrder(String setId) {
        return toolSetMemberRepository.findBySetIdOrderBySortOrderAsc(setId).stream()
                .mapToInt(ToolSetMemberEntity::getSortOrder)
                .max()
                .orElse(-1) + 1;
    }

    /** Admin 写：仅新 set；全局缺失时按种子 id/type 创建 */
    private WritableSet resolveWritableSet(ToolSetKind kind, String tenantId) {
        if (isTenantScoped(tenantId)) {
            String tid = tenantId.strip();
            ToolSetEntity tenantSet = toolSetRepository.findBySetTypeAndTenantId(kind.tenantType(), tid)
                    .orElseGet(() -> createTenantSet(kind, tid));
            return new WritableSet(tenantSet);
        }
        ToolSetEntity global = toolSetRepository.findBySetTypeAndTenantId(kind.globalType(), null)
                .orElseGet(() -> createGlobalSet(kind));
        return new WritableSet(global);
    }

    private Optional<ToolSetEntity> findSet(ToolSetKind kind, String tenantId) {
        if (isTenantScoped(tenantId)) {
            return toolSetRepository.findBySetTypeAndTenantId(kind.tenantType(), tenantId.strip());
        }
        return toolSetRepository.findBySetTypeAndTenantId(kind.globalType(), null);
    }

    private Optional<ToolSetEntity> findLegacySet(ToolSetKind kind, String tenantId) {
        if (isTenantScoped(tenantId)) {
            return toolSetRepository.findBySetTypeAndTenantId(kind.legacyTenantType(), tenantId.strip());
        }
        return toolSetRepository.findBySetTypeAndTenantId(kind.legacyGlobalType(), null);
    }

    private ToolSetEntity createGlobalSet(ToolSetKind kind) {
        ToolSetEntity entity = new ToolSetEntity();
        entity.setId(kind.globalSetId());
        entity.setSetType(kind.globalType());
        entity.setTenantId(null);
        entity.setDisplayName(kind == ToolSetKind.CHAT_DEFAULT ? "平台 Chat 工具集" : "平台 Task 工具集");
        entity.setUpdatedAt(Instant.now());
        return toolSetRepository.save(entity);
    }

    private ToolSetEntity createTenantSet(ToolSetKind kind, String tenantId) {
        ToolSetEntity entity = new ToolSetEntity();
        entity.setId(kind.tenantSetIdPattern().formatted(tenantId));
        entity.setSetType(kind.tenantType());
        entity.setTenantId(tenantId);
        entity.setDisplayName(kind.tenantDisplayName());
        entity.setUpdatedAt(Instant.now());
        return toolSetRepository.save(entity);
    }

    private Map<String, String> loadSourceLabels() {
        Map<String, String> labels = new HashMap<>();
        for (SdkApplicationEntity app : sdkApplicationRepository.findAll()) {
            labels.put("sdk\0" + app.getId(), StringUtils.hasText(app.getDisplayName()) ? app.getDisplayName() : app.getId());
        }
        for (McpServerEntity server : mcpServerRepository.findAll()) {
            labels.put("mcp\0" + server.getId(), StringUtils.hasText(server.getDisplayName()) ? server.getDisplayName() : server.getId());
        }
        return labels;
    }

    private String formatSourceTitle(String source, String sourceRef, Map<String, String> labels) {
        String label = labels.getOrDefault(source + "\0" + sourceRef, sourceRef);
        return ("sdk".equals(source) ? "SDK" : "MCP") + " · " + label;
    }

    private boolean matchesQuery(ToolSetMemberItemResponse item, String q) {
        return item.displayName().toLowerCase().contains(q)
                || item.toolId().toLowerCase().contains(q)
                || item.description().toLowerCase().contains(q)
                || item.sourceLabel().toLowerCase().contains(q);
    }

    private boolean matchesToolQuery(ToolDefinitionEntity tool, Map<String, String> sourceLabels, String q) {
        String title = formatSourceTitle(tool.getSource(), tool.getSourceRef(), sourceLabels);
        return tool.getDisplayName().toLowerCase().contains(q)
                || tool.getId().toLowerCase().contains(q)
                || (tool.getDescription() != null && tool.getDescription().toLowerCase().contains(q))
                || title.toLowerCase().contains(q);
    }

    private void touchSet(ToolSetEntity set) {
        set.setUpdatedAt(Instant.now());
        toolSetRepository.save(set);
    }

    private void publish(String tenantId) {
        if (catalogChangePublisher != null) {
            catalogChangePublisher.publish(tenantId);
        }
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
    }

    private static boolean isTenantScoped(String tenantId) {
        return StringUtils.hasText(tenantId) && !"default".equalsIgnoreCase(tenantId.strip());
    }

    private record WritableSet(ToolSetEntity set) {
    }
}
