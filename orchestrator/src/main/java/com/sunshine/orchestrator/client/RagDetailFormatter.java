package com.sunshine.orchestrator.client;

import java.util.List;
import java.util.stream.Collectors;

/** RAG 检索结果摘要，供时间线与 Agent 步骤展示 */
public final class RagDetailFormatter {

    private RagDetailFormatter() {
    }

    public static String formatDetail(List<RagClient.RagHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "命中 0 条";
        }
        String docNames = hits.stream()
                .map(RagClient.RagHit::docName)
                .distinct()
                .collect(Collectors.joining("、"));
        return "命中 " + hits.size() + " 条，来源：" + docNames;
    }
}
