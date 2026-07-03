package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.admin.eval.dto.EvalSuiteCreateRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuiteDetail;
import com.sunshine.rag.admin.eval.dto.EvalSuiteItemView;
import com.sunshine.rag.admin.eval.dto.EvalSuiteQueryRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuiteSummary;
import com.sunshine.rag.admin.eval.dto.EvalSuiteUpdateRequest;
import com.sunshine.rag.entity.EvalSuiteEntity;
import com.sunshine.rag.entity.EvalSuiteItemEntity;
import com.sunshine.rag.repository.EvalSuiteItemRepository;
import com.sunshine.rag.repository.EvalSuiteRepository;
import com.sunshine.rag.storage.RagStorageFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class EvalSuiteService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern USER_SUITE_KEY = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern KB_CUSTOM_SUITE_KEY = Pattern.compile("^[a-zA-Z0-9_-]+-custom$");

    private final EvalSuiteRepository evalSuiteRepository;
    private final EvalSuiteItemRepository evalSuiteItemRepository;
    private final RagStorageFacade storageFacade;
    private final EvalSuiteConfigParser configParser;
    private final ObjectMapper objectMapper;

    public List<EvalSuiteSummary> list(String tenantId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        return evalSuiteRepository.findByTenantIdOrderByUpdatedAtDesc(tid).stream()
                .map(this::toSummary)
                .toList();
    }

    public EvalSuiteDetail get(String tenantId, String suiteKey) {
        EvalSuiteEntity entity = requireSuite(tenantId, suiteKey);
        return toDetail(entity, true, true);
    }

    @Transactional
    public EvalSuiteDetail create(String tenantId, EvalSuiteCreateRequest request) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String key = requireSuiteKey(request.suiteKey());
        if (EvalSuiteKeys.isBuiltin(key)) {
            throw new IllegalArgumentException("不可使用内置评测集 Key");
        }
        if (evalSuiteRepository.findByTenantIdAndSuiteKey(tid, key).isPresent()) {
            throw new IllegalArgumentException("suiteKey 已存在: " + key);
        }
        validateNewSuiteKey(key);
        String kind = normalizeKind(request.kind());
        if ("python".equals(kind)) {
            if (request.content() == null || request.content().isBlank()) {
                throw new IllegalArgumentException("python 评测集须上传 content 脚本");
            }
            return toDetail(createPythonSuite(tid, key, request), true, true);
        }
        Map<String, Object> config = request.config() != null ? request.config() : configParser.defaultConfig();
        mergeHooks(config, request.hooks());
        EvalSuiteEntity entity = new EvalSuiteEntity();
        entity.setTenantId(tid);
        entity.setSuiteKey(key);
        entity.setDisplayName(blankToDefault(request.displayName(), key));
        entity.setDescription(blankToNull(request.description()));
        entity.setKind("standard");
        entity.setFormat("json");
        entity.setStorage("mysql");
        entity.setSchemaVersion(1);
        entity.setConfigJson(configParser.write(config));
        entity.setHooksJson(writeHooks(extractHooks(config)));
        entity.setItemCount(0);
        entity.setStatus("active");
        touchTimestamps(entity);
        return toDetail(evalSuiteRepository.save(entity), true, true);
    }

    @Transactional
    public EvalSuiteDetail update(String tenantId, String suiteKey, EvalSuiteUpdateRequest request) {
        String key = EvalSuiteKeys.normalizeSuiteKey(suiteKey);
        if (EvalSuiteKeys.isBuiltin(key)) {
            throw new IllegalArgumentException("内置评测集不可编辑");
        }
        EvalSuiteEntity entity = requireSuite(tenantId, key);
        if (!"standard".equals(entity.getKind())) {
            throw new IllegalArgumentException("仅 standard 评测集支持配置更新");
        }
        if (request.displayName() != null && !request.displayName().isBlank()) {
            entity.setDisplayName(request.displayName().strip());
        }
        if (request.description() != null) {
            entity.setDescription(blankToNull(request.description()));
        }
        if (request.config() != null) {
            Map<String, Object> config = request.config();
            if (request.hooks() != null) {
                mergeHooks(config, request.hooks());
            }
            entity.setConfigJson(configParser.write(config));
            entity.setHooksJson(writeHooks(extractHooks(config)));
        } else if (request.hooks() != null) {
            Map<String, Object> config = configParser.parse(entity.getConfigJson());
            mergeHooks(config, request.hooks());
            entity.setConfigJson(configParser.write(config));
            entity.setHooksJson(writeHooks(extractHooks(config)));
        }
        touchTimestamps(entity);
        return toDetail(evalSuiteRepository.save(entity), true, true);
    }

    @Transactional
    public void delete(String tenantId, String suiteKey) {
        String key = EvalSuiteKeys.normalizeSuiteKey(suiteKey);
        if (EvalSuiteKeys.isBuiltin(key)) {
            throw new IllegalArgumentException("内置评测集不可删除");
        }
        EvalSuiteEntity entity = requireSuite(tenantId, key);
        if ("python".equals(entity.getKind()) && entity.getContentRef() != null) {
            storageFacade.deleteSuiteText(
                    entity.getTenantId(), entity.getSuiteKey(), "suite.py", entity.getContentRef());
        }
        evalSuiteItemRepository.deleteBySuiteId(entity.getId());
        evalSuiteRepository.delete(entity);
    }

    @Transactional
    public EvalSuiteDetail ensureKbCustomSuite(String tenantId, String kbId, String displayName) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        String suiteKey = EvalSuiteKeys.kbCustomSuiteKey(kid);
        if (evalSuiteRepository.findByTenantIdAndSuiteKey(tid, suiteKey).isPresent()) {
            return get(tid, suiteKey);
        }
        String name = displayName != null && !displayName.isBlank() ? displayName.strip() : kid;
        Map<String, Object> config = configParser.defaultConfig();
        config.put("hooks", Map.of("kbId", kid));
        return create(tid, new EvalSuiteCreateRequest(suiteKey, name, name, "standard", config, Map.of("kbId", kid), null));
    }

    @Transactional
    public EvalSuiteDetail mutateQuery(String tenantId, String suiteKey, EvalSuiteQueryRequest request) {
        String key = EvalSuiteKeys.normalizeSuiteKey(suiteKey);
        if (EvalSuiteKeys.isBuiltin(key)) {
            throw new IllegalArgumentException("该评测集只读，请先复制后再编辑");
        }
        EvalSuiteEntity entity = requireSuite(tenantId, key);
        if (!"standard".equals(entity.getKind())) {
            throw new IllegalArgumentException("仅 standard 评测集支持条目编辑");
        }
        String action = request.action() != null ? request.action().strip().toLowerCase() : "";
        switch (action) {
            case "add" -> addItem(entity, request);
            case "update" -> updateItem(entity, request);
            case "delete" -> deleteItem(entity, request);
            default -> throw new IllegalArgumentException("action 须为 add/update/delete");
        }
        entity.setItemCount(evalSuiteItemRepository.countBySuiteId(entity.getId()));
        touchTimestamps(entity);
        evalSuiteRepository.save(entity);
        return toDetail(entity, true, true);
    }

    public EvalSuiteEntity requireSuite(String tenantId, String suiteKey) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String key = EvalSuiteKeys.normalizeSuiteKey(suiteKey);
        return evalSuiteRepository.findByTenantIdAndSuiteKey(tid, key)
                .orElseThrow(() -> new IllegalArgumentException("评测集不存在: " + suiteKey));
    }

    public List<EvalSuiteItemEntity> loadItems(long suiteId) {
        return evalSuiteItemRepository.findBySuiteIdOrderBySortOrderAscItemKeyAsc(suiteId);
    }

    public String loadPythonContent(EvalSuiteEntity suite) {
        if (!"python".equals(suite.getKind())) {
            throw new IllegalArgumentException("非 python 评测集: " + suite.getSuiteKey());
        }
        return storageFacade.readSuiteText(
                suite.getTenantId(), suite.getSuiteKey(), "suite.py", suite.getContentRef());
    }

    private EvalSuiteEntity createPythonSuite(String tenantId, String suiteKey, EvalSuiteCreateRequest request) {
        storageFacade.putSuiteText(tenantId, suiteKey, "suite.py", request.content());
        String contentRef = storageFacade.suiteContentRef(tenantId, suiteKey, "suite.py");
        EvalSuiteEntity entity = new EvalSuiteEntity();
        entity.setTenantId(tenantId);
        entity.setSuiteKey(suiteKey);
        entity.setDisplayName(blankToDefault(request.displayName(), suiteKey));
        entity.setDescription(blankToNull(request.description()));
        entity.setKind("python");
        entity.setFormat("py");
        entity.setStorage("minio");
        entity.setSchemaVersion(1);
        entity.setContentRef(contentRef);
        entity.setConfigJson(configParser.write(configParser.defaultConfig()));
        entity.setHooksJson(writeHooks(request.hooks()));
        entity.setItemCount(1);
        entity.setStatus("active");
        touchTimestamps(entity);
        return evalSuiteRepository.save(entity);
    }

    private void addItem(EvalSuiteEntity entity, EvalSuiteQueryRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String itemKey = request.id() != null && !request.id().isBlank()
                ? request.id().strip()
                : nextItemKey(entity.getId());
        if (evalSuiteItemRepository.findBySuiteIdAndItemKey(entity.getId(), itemKey).isPresent()) {
            throw new IllegalArgumentException("条目 itemKey 已存在: " + itemKey);
        }
        EvalSuiteItemEntity item = buildItem(entity.getId(), itemKey, request);
        item.setSortOrder((int) evalSuiteItemRepository.countBySuiteId(entity.getId()));
        evalSuiteItemRepository.save(item);
    }

    private void updateItem(EvalSuiteEntity entity, EvalSuiteQueryRequest request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("update 须指定 id");
        }
        EvalSuiteItemEntity item = evalSuiteItemRepository.findBySuiteIdAndItemKey(entity.getId(), request.id().strip())
                .orElseThrow(() -> new IllegalArgumentException("条目不存在: " + request.id()));
        if (request.query() != null && !request.query().isBlank()) {
            item.setQueryText(request.query().strip());
        }
        if (request.relevantDocIds() != null) {
            item.setRelevantDocIdsJson(configParser.writeStringList(request.relevantDocIds()));
            item.setExpectEmpty(request.relevantDocIds().isEmpty());
            item.setItemType(request.relevantDocIds().isEmpty() ? "negative" : "positive");
        }
        if (request.category() != null && !request.category().isBlank()) {
            item.setCategory(request.category().strip());
        }
        item.setUpdatedAt(Instant.now());
        evalSuiteItemRepository.save(item);
    }

    private void deleteItem(EvalSuiteEntity entity, EvalSuiteQueryRequest request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("delete 须指定 id");
        }
        EvalSuiteItemEntity item = evalSuiteItemRepository.findBySuiteIdAndItemKey(entity.getId(), request.id().strip())
                .orElseThrow(() -> new IllegalArgumentException("条目不存在: " + request.id()));
        evalSuiteItemRepository.delete(item);
    }

    private EvalSuiteItemEntity buildItem(long suiteId, String itemKey, EvalSuiteQueryRequest request) {
        List<String> docIds = request.relevantDocIds() != null ? request.relevantDocIds() : List.of();
        EvalSuiteItemEntity item = new EvalSuiteItemEntity();
        item.setSuiteId(suiteId);
        item.setItemKey(itemKey);
        item.setQueryText(request.query().strip());
        item.setRelevantDocIdsJson(configParser.writeStringList(docIds));
        item.setRelevantKeywordsJson("[]");
        item.setCategory(request.category() != null && !request.category().isBlank()
                ? request.category().strip()
                : "custom");
        item.setExpectEmpty(docIds.isEmpty());
        item.setItemType(docIds.isEmpty() ? "negative" : "positive");
        return item;
    }

    private String nextItemKey(long suiteId) {
        List<EvalSuiteItemEntity> items = evalSuiteItemRepository.findBySuiteIdOrderBySortOrderAscItemKeyAsc(suiteId);
        int max = 0;
        for (EvalSuiteItemEntity item : items) {
            String key = item.getItemKey();
            if (key.startsWith("q") && key.length() > 1) {
                try {
                    max = Math.max(max, Integer.parseInt(key.substring(1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return "q" + String.format("%03d", max + 1);
    }

    private EvalSuiteSummary toSummary(EvalSuiteEntity entity) {
        return new EvalSuiteSummary(
                entity.getId(),
                entity.getSuiteKey(),
                entity.getDisplayName(),
                entity.getKind(),
                entity.getFormat(),
                entity.getItemCount(),
                entity.getStatus(),
                EvalSuiteKeys.isBuiltin(entity.getSuiteKey()),
                entity.getCreatedAt());
    }

    private EvalSuiteDetail toDetail(EvalSuiteEntity entity, boolean includeItems, boolean includePythonContent) {
        Map<String, Object> config = configParser.parse(entity.getConfigJson());
        String content = null;
        if (includePythonContent && "python".equals(entity.getKind())) {
            content = loadPythonContent(entity);
        }
        List<EvalSuiteItemView> items = includeItems && "standard".equals(entity.getKind())
                ? loadItems(entity.getId()).stream().map(this::toItemView).toList()
                : List.of();
        return new EvalSuiteDetail(
                entity.getId(),
                entity.getSuiteKey(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getKind(),
                entity.getFormat(),
                entity.getContentRef(),
                parseHooks(entity.getHooksJson()),
                config,
                entity.getItemCount(),
                entity.getStatus(),
                EvalSuiteKeys.isBuiltin(entity.getSuiteKey()),
                content,
                items);
    }

    private EvalSuiteItemView toItemView(EvalSuiteItemEntity entity) {
        return new EvalSuiteItemView(
                entity.getItemKey(),
                entity.getSortOrder(),
                entity.getQueryText(),
                entity.getItemType(),
                configParser.readStringList(entity.getRelevantDocIdsJson()),
                configParser.readStringList(entity.getRelevantKeywordsJson()),
                entity.getCategory(),
                entity.isExpectEmpty());
    }

    private static void mergeHooks(Map<String, Object> config, Map<String, Object> hooks) {
        if (hooks == null || hooks.isEmpty()) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        Object existing = config.get("hooks");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((k, v) -> merged.put(String.valueOf(k), v));
        }
        merged.putAll(hooks);
        config.put("hooks", merged);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractHooks(Map<String, Object> config) {
        Object hooks = config.get("hooks");
        if (hooks instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Map<String, Object> parseHooks(String json) {
        try {
            return objectMapper.readValue(json != null ? json : "{}", MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeHooks(Map<String, Object> hooks) {
        try {
            return objectMapper.writeValueAsString(hooks != null ? hooks : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void touchTimestamps(EvalSuiteEntity entity) {
        Instant now = Instant.now();
        if (entity.getId() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
    }

    private static String requireSuiteKey(String suiteKey) {
        if (suiteKey == null || suiteKey.isBlank()) {
            throw new IllegalArgumentException("suiteKey 不能为空");
        }
        return suiteKey.strip();
    }

    private static void validateNewSuiteKey(String suiteKey) {
        if (EvalSuiteKeys.isBuiltin(suiteKey)) {
            throw new IllegalArgumentException("不可使用内置评测集 Key");
        }
        if (USER_SUITE_KEY.matcher(suiteKey).matches() || KB_CUSTOM_SUITE_KEY.matcher(suiteKey).matches()) {
            return;
        }
        throw new IllegalArgumentException("suiteKey 仅支持字母、数字、下划线，且不能以数字开头");
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return "standard";
        }
        String normalized = kind.strip().toLowerCase();
        if ("standard".equals(normalized) || "python".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("kind 须为 standard 或 python");
    }

    private static String blankToDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value.strip() : fallback;
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.strip() : null;
    }
}
