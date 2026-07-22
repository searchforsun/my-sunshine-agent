package com.sunshine.orchestrator.memory.mtm;

import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 旧 MTM 表已 DROP；Task 8 前跳过摘要写入。
 */
@Slf4j
@Service
public class MtmSummarizeService {

    @Async
    public void summarizeIfNeeded(
            String userId,
            String tenantId,
            String convId,
            String intent,
            List<ChatMessageEntity> messages) {
        // no-op：conversation_memory_mtm 已废止
    }
}
