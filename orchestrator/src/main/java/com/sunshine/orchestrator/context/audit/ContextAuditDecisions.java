package com.sunshine.orchestrator.context.audit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** L2/L1 审计 LLM 输出契约（包内共享）。 */
final class ContextAuditDecisions {

    private ContextAuditDecisions() {
    }

    record L2AuditDecision(Set<String> voidIds, Set<String> conflictIds) {
        static L2AuditDecision empty() {
            return new L2AuditDecision(Set.of(), Set.of());
        }
    }

    record L1AuditDecision(Map<String, List<String>> removeMidKeys, Map<String, String> farSummaryByConv) {
        static L1AuditDecision empty() {
            return new L1AuditDecision(Map.of(), Map.of());
        }
    }
}
