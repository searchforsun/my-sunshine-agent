package com.sunshine.rag.admin.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentParseAsyncRunner {

    private final DocumentParseJobService parseJobService;

    @Async("parseTaskExecutor")
    public void run(long jobId) {
        parseJobService.executeJob(jobId);
    }
}
