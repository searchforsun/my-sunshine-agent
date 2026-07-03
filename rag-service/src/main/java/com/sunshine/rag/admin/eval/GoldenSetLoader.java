package com.sunshine.rag.admin.eval;

import com.sunshine.rag.admin.catalog.DocumentCatalogService;
import com.sunshine.rag.admin.catalog.dto.DocumentSummary;
import com.sunshine.rag.entity.EvalSuiteEntity;
import com.sunshine.rag.entity.EvalSuiteItemEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GoldenSetLoader {

    private final EvalSuiteService evalSuiteService;
    private final EvalSuiteConfigParser configParser;
    private final DocumentCatalogService documentCatalogService;

    public GoldenSetLoader(
            @Lazy EvalSuiteService evalSuiteService,
            EvalSuiteConfigParser configParser,
            DocumentCatalogService documentCatalogService) {
        this.evalSuiteService = evalSuiteService;
        this.configParser = configParser;
        this.documentCatalogService = documentCatalogService;
    }

    public record GoldenQuery(String id, String query, List<String> relevantDocIds, String category) {
        boolean positive() {
            return relevantDocIds != null && !relevantDocIds.isEmpty();
        }
    }

    public record EvalSettings(List<Integer> topK, float minScore, Map<String, Object> gates) {
    }

    public record GoldenSetData(int version, EvalSettings eval, List<GoldenQuery> queries) {
    }

    public GoldenSetData load() {
        return load("default", EvalSuiteKeys.REGRESSION);
    }

    public GoldenSetData load(String tenantId, String suiteKey) {
        EvalSuiteEntity suite = evalSuiteService.requireSuite(tenantId, suiteKey);
        if ("python".equals(suite.getKind())) {
            throw new IllegalArgumentException("python 评测集不可用于标准检索评测: " + suiteKey);
        }
        Map<String, Object> config = configParser.parse(suite.getConfigJson());
        EvalSettings eval = configParser.toEvalSettings(config);
        List<GoldenQuery> queries = loadQueriesFromDb(suite.getId());
        return new GoldenSetData(suite.getSchemaVersion(), eval, queries);
    }

    /** 冒烟门禁用例（sunshine-smoke 评测集） */
    public List<GoldenQuery> smokeQueries(String tenantId) {
        return load(tenantId, EvalSuiteKeys.SMOKE).queries().stream()
                .filter(GoldenQuery::positive)
                .toList();
    }

    /** 从目标知识库 document 表解析 docId→displayName（评测命中判定 SSOT） */
    public Map<String, String> docIdToDisplayName(String tenantId, String kbId) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (DocumentSummary doc : documentCatalogService.listDocuments(tenantId, kbId)) {
            mapping.put(doc.docId(), doc.displayName());
        }
        return mapping;
    }

    private List<GoldenQuery> loadQueriesFromDb(long suiteId) {
        List<EvalSuiteItemEntity> items = evalSuiteService.loadItems(suiteId);
        List<GoldenQuery> result = new ArrayList<>();
        for (EvalSuiteItemEntity item : items) {
            List<String> docIds = configParser.readStringList(item.getRelevantDocIdsJson());
            result.add(new GoldenQuery(item.getItemKey(), item.getQueryText(), docIds, item.getCategory()));
        }
        return result;
    }
}
