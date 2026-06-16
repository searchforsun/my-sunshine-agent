package com.sunshine.orchestrator.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.ProcessingStep;
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
                        e -> {
                            if (e instanceof ConversationNotFoundException) {
                                log.debug("[Flush] partial 跳过 msgId={}: 消息已不存在", messageId);
                                return;
                            }
                            log.warn("[Flush] partial 写库失败 msgId={}: {}", messageId, e.getMessage());
                        }
                );
    }

    public void commitFinal(String messageId, String content, String status) {
        commitFinal(messageId, content, null, status);
    }

    public void commitFinal(String messageId, String content, String reasoning, String status) {
        commitFinal(messageId, content, reasoning, status, null);
    }

    public void commitFinal(String messageId, String content, String reasoning, String status, String stepsJson) {
        conversationService.updateMessage(messageId, content, reasoning, status, stepsJson);
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

    /** 处理步骤 — 结构化 SSE，不写入消息正文 */
    public String metaStep(ProcessingStep step) {
        try {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "step");
            map.put("id", step.id());
            map.put("phase", step.phase());
            if (step.lifecycle() != null) {
                map.put("lifecycle", step.lifecycle());
            }
            if (step.summary() != null) {
                java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
                if (step.summary().before() != null) {
                    summary.put("before", step.summary().before());
                }
                if (step.summary().active() != null) {
                    summary.put("active", step.summary().active());
                }
                if (step.summary().after() != null) {
                    summary.put("after", step.summary().after());
                }
                if (!summary.isEmpty()) {
                    map.put("summary", summary);
                }
            }
            if (step.startedAt() != null) {
                map.put("startedAt", step.startedAt());
            }
            if (step.endedAt() != null) {
                map.put("endedAt", step.endedAt());
            }
            if (step.durationMs() != null) {
                map.put("durationMs", step.durationMs());
            }
            if (step.detail() != null) {
                map.put("detail", step.detail());
            }
            if (step.reasoning() != null) {
                map.put("reasoning", step.reasoning());
            }
            if (step.output() != null) {
                map.put("output", step.output());
            }
            if (step.result() != null) {
                map.put("result", step.result());
            }
            map.put("ts", step.ts());
            map.put("status", step.status());
            map.put("label", step.label());
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"step\",\"id\":\"" + step.id() + "\",\"status\":\""
                    + step.status() + "\",\"label\":\"" + step.label() + "\"}";
        }
    }

    /** 步骤流式增量 — 结构化 SSE */
    public String metaStepDelta(String stepId, String channel, String text) {
        try {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "step_delta");
            map.put("v", 1);
            map.put("stepId", stepId);
            map.put("channel", channel);
            map.put("text", text);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"step_delta\",\"v\":1,\"stepId\":\"" + stepId
                    + "\",\"channel\":\"" + channel + "\",\"text\":\"\"}";
        }
    }

    /** 推理过程 — 结构化 SSE，不写入消息正文 */
    public String metaReasoning(String text) {
        return metaText("reasoning", text);
    }

    /** 正文 content — 结构化 SSE */
    public String metaContent(String text) {
        return metaText("content", text);
    }

    private String metaText(String type, String text) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "type", type,
                    "text", text
            ));
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\",\"text\":\"\"}";
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
