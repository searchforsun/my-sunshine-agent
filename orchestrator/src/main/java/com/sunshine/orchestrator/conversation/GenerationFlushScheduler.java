package com.sunshine.orchestrator.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 流式生成期间周期性 flush partial 到 MySQL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationFlushScheduler {

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Consumer<String> createChunkAppender(StringBuilder buffer, String messageId, long flushIntervalMs) {
        AtomicLong lastFlush = new AtomicLong(0);
        return chunk -> {
            buffer.append(chunk);
            long now = System.currentTimeMillis();
            if (now - lastFlush.get() >= flushIntervalMs) {
                lastFlush.set(now);
                flushPartial(messageId, buffer.toString());
            }
        };
    }

    public void flushPartial(String messageId, String content) {
        Mono.fromRunnable(() -> conversationService.updateMessageContent(
                        messageId, content, MessageStatus.STREAMING))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        e -> log.warn("[Flush] partial 写库失败 msgId={}: {}", messageId, e.getMessage())
                );
    }

    public void commitFinal(String messageId, String content, String status) {
        conversationService.updateMessageContent(messageId, content, status);
    }

    public String metaConversation(String convId) {
        return toMetaJson("conversation", convId, null, false);
    }

    public String metaMessage(String msgId, String status, boolean resume) {
        return toMetaJson("message", msgId, status, resume);
    }

    public String metaGeneration(String generationId, String messageId) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "type", "generation",
                    "id", generationId,
                    "messageId", messageId,
                    "seq", 0
            ));
        } catch (Exception e) {
            return "{\"type\":\"generation\",\"id\":\"" + generationId
                    + "\",\"messageId\":\"" + messageId + "\",\"seq\":0}";
        }
    }

    private String toMetaJson(String type, String id, String status, boolean resume) {
        try {
            if ("conversation".equals(type)) {
                return objectMapper.writeValueAsString(
                        java.util.Map.of("type", "conversation", "id", id));
            }
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "message");
            map.put("id", id);
            if (status != null) {
                map.put("status", status);
            }
            map.put("resume", resume);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\",\"id\":\"" + id + "\"}";
        }
    }
}
