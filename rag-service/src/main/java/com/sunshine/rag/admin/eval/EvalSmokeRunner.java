package com.sunshine.rag.admin.eval;

import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.admin.config.ResolvedKbConfig;
import com.sunshine.rag.admin.eval.dto.FailedEvalSample;
import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;
import com.sunshine.rag.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置发布前 smoke 评测 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalSmokeRunner {

    private final EffectiveConfigResolver effectiveConfigResolver;
    private final GoldenSetLoader goldenSetLoader;
    private final EvalRetrievalProbe retrievalProbe;
    private final EvalReportPersister reportPersister;

    public SmokeEvalResult smokeEvalBundle(String tenantId, String kbId, Map<String, Object> bundlePayload) {
        ResolvedKbConfig resolved = effectiveConfigResolver.resolvePayload(tenantId, kbId, bundlePayload);
        EffectiveRagConfig config = resolved.retrieval();
        Map<String, String> id2name = goldenSetLoader.docIdToDisplayName(tenantId, kbId);
        List<GoldenSetLoader.GoldenQuery> queries = goldenSetLoader.smokeQueries(tenantId);
        double baseline = reportPersister.resolveBaselineRecallAt5();
        List<Double> recalls = new ArrayList<>();
        List<FailedEvalSample> failedSamples = new ArrayList<>();
        for (GoldenSetLoader.GoldenQuery item : queries) {
            Set<String> relevant = EvalRetrievalProbe.resolveRelevantNames(item, id2name);
            if (relevant.isEmpty()) {
                continue;
            }
            List<RetrievalService.DocFragment> hits = retrievalProbe.searchHits(
                    item.query(), 5, tenantId, kbId, null, true, config);
            double recall = EvalMetrics.recallAtK(hits, relevant, 5, config.minScore());
            recalls.add(recall);
            if (recall < 1.0) {
                failedSamples.add(new FailedEvalSample(
                        item.id(),
                        item.query(),
                        List.copyOf(relevant),
                        hits.stream().map(RetrievalService.DocFragment::docName).limit(5).toList()));
            }
        }
        double recallAt5 = recalls.isEmpty() ? 0.0
                : recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        boolean passedGate = recallAt5 >= baseline;
        log.info("[RAG] smoke eval bundle kb={} recall@5={} baseline={} passed={}",
                kbId, recallAt5, baseline, passedGate);
        return new SmokeEvalResult(recallAt5, baseline, passedGate, failedSamples);
    }
}
