package com.sunshine.rag.admin.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EvalAsyncRunner {

    private final ObjectProvider<EvaluateService> evaluateServiceProvider;

    @Async("evalTaskExecutor")
    public void runJob(long jobId) {
        evaluateServiceProvider.getObject().executeJob(jobId);
    }
}
