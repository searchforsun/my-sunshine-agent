package com.sunshine.rag.admin.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EvalAsyncRunner {

    private final EvaluateService evaluateService;

    @Async("evalTaskExecutor")
    public void runJob(long jobId) {
        evaluateService.executeJob(jobId);
    }
}
