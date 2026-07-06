package com.sunshine.rag.admin.eval;

import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvalJobRecoveryRunnerTest {

    @Mock
    private EvalJobRepository evalJobRepository;
    @Mock
    private EvalAsyncRunner evalAsyncRunner;

    @InjectMocks
    private EvalJobRecoveryRunner recoveryRunner;

    @Test
    void recoversPendingAndRunningJobs() throws Exception {
        EvalJobEntity running = new EvalJobEntity();
        running.setId(5L);
        running.setStatus("running");
        running.setProcessedItems(30);
        running.setTotalItems(123);
        when(evalJobRepository.findByStatusInOrderByCreatedAtAsc(List.of("pending", "running")))
                .thenReturn(List.of(running));
        recoveryRunner.run(new DefaultApplicationArguments(new String[0]));
        verify(evalAsyncRunner).runJob(5L);
    }

    @Test
    void skipsWhenNoInterruptedJobs() throws Exception {
        when(evalJobRepository.findByStatusInOrderByCreatedAtAsc(List.of("pending", "running")))
                .thenReturn(List.of());
        recoveryRunner.run(new DefaultApplicationArguments(new String[0]));
        verifyNoInteractions(evalAsyncRunner);
    }
}
