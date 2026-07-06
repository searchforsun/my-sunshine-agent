package com.sunshine.rag.admin.eval;

import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** 服务重启后恢复未完成的评测任务（pending / running）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalJobRecoveryRunner implements ApplicationRunner {

    private static final List<String> RECOVERABLE = List.of("pending", "running");

    private final EvalJobRepository evalJobRepository;
    private final EvalAsyncRunner evalAsyncRunner;

    @Override
    public void run(ApplicationArguments args) {
        List<EvalJobEntity> jobs = evalJobRepository.findByStatusInOrderByCreatedAtAsc(RECOVERABLE);
        if (jobs.isEmpty()) {
            return;
        }
        log.warn("[RAG] 恢复 {} 个未完成评测任务", jobs.size());
        for (EvalJobEntity job : jobs) {
            log.warn("[RAG] 恢复评测 jobId={} status={} progress={}/{}",
                    job.getId(), job.getStatus(), job.getProcessedItems(), job.getTotalItems());
            evalAsyncRunner.runJob(job.getId());
        }
    }
}
