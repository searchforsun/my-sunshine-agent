package com.sunshine.orchestrator.plan.harness;

import java.util.Optional;

public interface PlanNotebookStore {
    void save(PlanNotebook notebook);
    Optional<PlanNotebook> load(String sessionId);
    void delete(String sessionId);
    void renewTtl(String sessionId);
}
