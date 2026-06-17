package com.sunshine.orchestrator.memory.mtm;

import com.sunshine.orchestrator.client.DesensitizeClient;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.memory.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 会话结束后 LLM 摘要，写入 MTM（MySQL + Milvus）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MtmSummarizeService {

    private static final int MIN_MESSAGES = 2;

    private final MtmService mtmService;
    private final LlmGatewayClient llmGatewayClient;
    private final DesensitizeClient desensitizeClient;
    private final MemoryProperties memoryProperties;

    @Async
    public void summarizeIfNeeded(
            String userId,
            String tenantId,
            String convId,
            String intent,
            List<ChatMessageEntity> messages) {
        if (!memoryProperties.isEnabled() || !memoryProperties.getMtm().isEnabled()) {
            return;
        }
        if (messages == null || messages.size() < MIN_MESSAGES) {
            return;
        }

        String transcript = buildTranscript(messages);
        if (!StringUtils.hasText(transcript)) {
            return;
        }

        String prompt = memoryProperties.getMtm().getSummarizePrompt().strip();
        String summary = llmGatewayClient.complete(
                prompt,
                "以下是待摘要的会话 transcript：\n\n" + transcript);

        if (!StringUtils.hasText(summary)) {
            log.warn("[MTM] 摘要为空 conv={}", convId);
            return;
        }

        String scrubbed = desensitizeClient.scrub(summary.strip());
        mtmService.saveSummary(userId, tenantId, convId, scrubbed, intent);
    }

    private String buildTranscript(List<ChatMessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessageEntity m : messages) {
            if (!"user".equals(m.getRole()) && !"assistant".equals(m.getRole())) {
                continue;
            }
            if (!StringUtils.hasText(m.getContent())) {
                continue;
            }
            sb.append(m.getRole()).append(": ")
                    .append(truncate(m.getContent().strip(), 800))
                    .append("\n");
        }
        return sb.toString().strip();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
