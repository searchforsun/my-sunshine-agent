package com.sunshine.orchestrator.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.client.DesensitizeClient;
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
    private final DesensitizeClient desensitizeClient;
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
        Mono.fromRunnable(() -> conversationService.updateMessageContentIfStreaming(
                        messageId, content))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        e -> {
                            if (e instanceof BizException biz
                                    && (biz.getErrorCode() == OrchestratorErrorCode.MESSAGE_NOT_FOUND
                                    || biz.getErrorCode() == OrchestratorErrorCode.CONVERSATION_NOT_FOUND)) {
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
        commitFinal(messageId, content, reasoning, status, stepsJson, null);
    }

    public void commitFinal(
            String messageId,
            String content,
            String reasoning,
            String status,
            String stepsJson,
            String contentBlocksJson) {
        String scrubbed = desensitizeClient.scrub(content);
        conversationService.updateMessage(messageId, scrubbed, reasoning, status, stepsJson, contentBlocksJson);
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
            // 前端只需当前阶段一行摘要，不必同时下发 before/active/after
            StepSummary phaseSummary = ProcessingStepMerger.currentPhaseSummary(step);
            if (phaseSummary != null) {
                java.util.Map<String, Object> summary = ProcessingStepMerger.summaryToMap(phaseSummary);
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
            if (step.metadata() != null && !step.metadata().isEmpty()) {
                map.put("metadata", ProcessingStepMerger.metadataToMap(step.metadata()));
            }
            if (step.subSteps() != null && !step.subSteps().isEmpty()) {
                java.util.List<java.util.Map<String, Object>> nested = new java.util.ArrayList<>();
                for (ProcessingStep sub : step.subSteps()) {
                    nested.add(ProcessingStepMerger.stepToMap(sub));
                }
                map.put("subSteps", nested);
            }
            map.put("ts", step.ts());
            map.put("label", step.label());
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            String lc = step.lifecycle() != null ? step.lifecycle() : "running";
            return "{\"type\":\"step\",\"id\":\"" + step.id() + "\",\"lifecycle\":\""
                    + lc + "\",\"label\":\"" + step.label() + "\"}";
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

    /** 流式失败 — type:error，供前端展示 */
    public String metaError(String text) {
        return metaText("error", text);
    }

    /** ReAct 正文段开始 */
    public String metaContentStart(String segmentId, String afterStepId) {
        return metaContentStart(segmentId, afterStepId, null);
    }

    public String metaContentStart(String segmentId, String afterStepId, String nodeStepId) {
        try {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "content_start");
            map.put("segmentId", segmentId);
            map.put("afterStepId", afterStepId);
            putNodeStepId(map, nodeStepId);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"content_start\",\"segmentId\":\"" + segmentId
                    + "\",\"afterStepId\":\"" + afterStepId + "\"}";
        }
    }

    /** ReAct 正文段结束 */
    public String metaContentEnd(String segmentId) {
        return metaContentEnd(segmentId, null);
    }

    public String metaContentEnd(String segmentId, String nodeStepId) {
        try {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "content_end");
            map.put("segmentId", segmentId);
            putNodeStepId(map, nodeStepId);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"content_end\",\"segmentId\":\"" + segmentId + "\"}";
        }
    }

    /** ReAct 段内正文增量 */
    public String metaContentInSegment(String segmentId, String text) {
        return metaContentInSegment(segmentId, text, null);
    }

    public String metaContentInSegment(String segmentId, String text, String nodeStepId) {
        try {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "content");
            map.put("segmentId", segmentId);
            map.put("text", text);
            putNodeStepId(map, nodeStepId);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"content\",\"segmentId\":\"" + segmentId + "\",\"text\":\"\"}";
        }
    }

    private static void putNodeStepId(java.util.Map<String, Object> map, String nodeStepId) {
        if (nodeStepId != null && !nodeStepId.isBlank()) {
            map.put("nodeStepId", nodeStepId);
        }
    }

    /** 正文 content — 结构化 SSE；simple-llm 穿插时带 afterStepId */
    public String metaContent(String text) {
        return metaContent(text, null);
    }

    public String metaContent(String text, String afterStepId) {
        try {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "content");
            map.put("text", text);
            if (afterStepId != null && !afterStepId.isBlank()) {
                map.put("afterStepId", afterStepId);
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"content\",\"text\":\"\"}";
        }
    }

    /** 写工具 HITL 确认 — 结构化 SSE */
    public String metaConfirmation(
            String toolId,
            String toolDisplayName,
            String paramsSummary,
            String confirmationToken,
            long expiresAt) {
        try {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("type", "confirmation");
            map.put("toolId", toolId);
            map.put("toolDisplayName", toolDisplayName);
            map.put("paramsSummary", paramsSummary != null ? paramsSummary : "");
            map.put("confirmationToken", confirmationToken);
            map.put("expiresAt", expiresAt);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"confirmation\",\"toolId\":\"" + toolId
                    + "\",\"confirmationToken\":\"" + confirmationToken + "\"}";
        }
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
